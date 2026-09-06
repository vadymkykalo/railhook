package com.webhook.platform.api.domain;

import org.flywaydb.core.Flyway;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity ratchet over the entity duplication the two modules keep.
 *
 * <p>{@code api} and {@code worker} each keep their own {@code @Entity} copy of
 * every shared table, deliberately. The cost is that a schema change is a
 * three-file change — migration, api entity, worker entity — and that nothing in the build
 * noticed when only two of the three happened. Hibernate's {@code ddl-auto: validate} catches a
 * mapping that names a column the schema does not have; it cannot catch a column the schema has
 * and an entity never mentions. That direction is silent by construction, and it has cost two
 * production bugs:
 *
 * <ul>
 *   <li>{@code events.payload_compressed} unmapped by the worker's {@code Event}: every Event
 *       above the 1 KB compression threshold was delivered — and HMAC-signed — as a gzip+Base64
 *       blob instead of JSON.</li>
 *   <li>{@code endpoints.deleted_at} unmapped by the worker's {@code Endpoint}: a soft-deleted
 *       Endpoint kept receiving Deliveries for the whole life of its Retry Ladder.</li>
 * </ul>
 *
 * <p>Both columns had been in the schema for releases. This test makes that shape of gap fail
 * the build instead: for every table mapped by <em>both</em> modules, each schema column must be
 * mapped by both sides, or appear in {@link #DELIBERATELY_UNMAPPED} with a stated reason.
 *
 * <h2>Where the three inputs come from</h2>
 *
 * <ol>
 *   <li><b>Schema</b> — Flyway runs the real migrations into a throwaway Postgres and the column
 *       set is read from {@code information_schema.columns}. Deriving it by reading the SQL was
 *       tried and rejected: the schema is 55 migrations deep, and V052/V053 rebuild
 *       {@code delivery_attempts} and {@code tunnel_request_log} as partitioned tables by
 *       renaming the original aside and re-creating it, partly through dynamic SQL inside
 *       {@code DO $$} blocks. A hand-written DDL interpreter would be one more thing that can be
 *       silently wrong — which is the exact failure mode this test exists to remove. This is
 *       what this test does instead: compares each shared table's
 *       {@code information_schema} columns against both {@code @Entity} mappings.</li>
 *   <li><b>Which tables are shared</b> — derived from the filesystem, by intersecting the two
 *       entity package directories. A tenth shared entity is covered the day it is added; it is
 *       never a list anyone has to remember to update.</li>
 *   <li><b>What each side maps</b> — parsed out of the entity <em>sources</em> of both modules.
 *       The two modules are siblings in the reactor and neither depends on the other, so no
 *       module's test classpath can see both sets of classes. Adding a test-scoped edge was
 *       rejected: both modules ship an {@code application.yml} and a {@code logback-spring.xml}
 *       at the classpath root, so putting one on the other's test classpath makes which config
 *       wins a matter of jar order, and would quietly change how every existing
 *       {@code @SpringBootTest} in this module boots.</li>
 * </ol>
 *
 * <h2>Why the source parser can be trusted</h2>
 *
 * <p>A parser that under-reports a mapping produces a loud false failure someone investigates. A
 * parser that over-reports one hides real drift, which is the dangerous direction, so it is
 * checked three ways:
 *
 * <ul>
 *   <li>{@link #parsedMappingsResolveToRealColumns()} — every column either parser derives must
 *       exist in the migrated schema. Both modules run {@code ddl-auto: validate}, so a mapping
 *       the schema does not have could not boot; a column name this parser invents therefore
 *       fails here.</li>
 *   <li>{@link #sourceParserAgreesWithReflection()} — for the api side, whose classes <em>are</em>
 *       on this test's classpath, the source parser's answer must equal reflection's.</li>
 *   <li>{@link EntitySource#unhandled} — the parser never guesses. Any JPA construct it was not
 *       written for ({@code @Embedded}, a {@code @ManyToOne} with no {@code @JoinColumn}, a
 *       multi-name field declaration) is reported and fails the build, so extending the entities
 *       into new territory forces extending the parser rather than silently widening a blind
 *       spot.</li>
 * </ul>
 *
 * <p>Named {@code *IntegrationTest} on purpose: it starts a container, so CI must route it to the
 * Docker job — see {@code scripts/check-test-routing.sh}.
 */
@Testcontainers
@Tag("ratchet")
class EntityMappingParityIntegrationTest {

    /**
     * Schema columns that are deliberately not mapped by one or both modules, and why.
     *
     * <p>Key is {@code table.column}; value is the reason. An entry here is a claim that the
     * omission is intentional and safe — the same weight as an entry in
     * {@code MutatingHandlerScopeDeclarationTest.DOCUMENTED_EXEMPTIONS}. Prefer mapping the
     * column. If you cannot say why a column is missing, it is drift, not an exemption.
     *
     * <p>Every entry below is a column the <em>worker</em> does not map; the api maps all of
     * them. Two reasons recur and are worth stating
     * once: a column can be safely unmapped either because only the api ever writes it, or
     * because the worker consumes its <em>outcome</em> through a different column it does map.
     * Neither reason survives the column being read on the delivery or forward path — which is
     * exactly what {@code payload_compressed} and {@code deleted_at} turned out to be.
     */
    private static final Map<String, String> DELIBERATELY_UNMAPPED = new TreeMap<>();

    static {
        // --- Outgoing: replay bookkeeping. Written by ReplayService when the api re-queues an
        // Event, to tie the new Delivery back to its Replay Session for the dashboard. The
        // worker never creates a Delivery row and never reads the link.
        exempt("deliveries.replay_session_id", "api-only: set by ReplayService, read by the dashboard");

        // --- Outgoing: Endpoint columns the delivery path does not consult.
        exempt("endpoints.description", "dashboard-only label; carries no delivery behaviour");
        exempt("endpoints.created_at", "informational; the worker neither reads nor writes it");

        // The four secret-rotation columns were exempt here with the note "IF DUAL-SIGNING
        // DURING A GRACE PERIOD IS EVER IMPLEMENTED, the worker is where it lands, and these
        // four entries must be deleted rather than re-justified." It was, and they are:
        // EndpointService.rotateSecret writes the retired secret and stamps rotated_at, and
        // OutgoingAttemptStore.secretInsideGraceWindow reads all four.

        // Endpoint-verification handshake: entirely an api flow. The worker consumes only its
        // outcome, through verification_status, which it does map — OutgoingAttemptStore
        // defers the Delivery unless that column reads VERIFIED or SKIPPED.
        exempt("endpoints.verification_token", "api-side verification handshake; the worker gates on verification_status");
        exempt("endpoints.verification_attempted_at", "api-side verification handshake; the worker gates on verification_status");
        exempt("endpoints.verification_completed_at", "api-side verification handshake; the worker gates on verification_status");
        exempt("endpoints.verification_skip_reason", "api-side verification handshake; the worker gates on verification_status");

        // --- Outgoing: ingest-time deduplication, resolved before the Event is announced.
        exempt("events.idempotency_key", "api-only: ingest dedup key, consumed before the outbox row exists");

        // --- Incoming: request capture kept as it arrived, for the dashboard's request
        // inspector. A Forward posts to destination.url verbatim (IncomingAttemptStore
        // .buildRequest) and reproduces none of the original request line.
        exempt("incoming_events.path", "capture metadata; a Forward posts to destination.url, not to the original path");
        exempt("incoming_events.query_params", "capture metadata; a Forward posts to destination.url, not to the original path");
        exempt("incoming_events.client_ip", "capture metadata; not forwarded, and not part of any Forward decision");
        exempt("incoming_events.user_agent", "capture metadata; the Forward sends its own User-Agent");

        // Ingress-side dedup and replay keys, consumed by IngressService before anything is
        // enqueued.
        exempt("incoming_events.body_sha256", "api-only: ingress replay key, checked before the outbox row exists");
        exempt("incoming_events.provider_event_id", "api-only: ingress dedup key, checked before the outbox row exists");

        // IngressService answers 401 and writes no row when a Source has a verification mode
        // and the signature does not check out, so a Forward can only ever see a verified
        // Incoming Event. These two exist for the dashboard's audit trail.
        exempt("incoming_events.verified", "api-only: an unverified webhook is rejected at ingress and never reaches a Forward");
        exempt("incoming_events.verification_error", "api-only: an unverified webhook is rejected at ingress and never reaches a Forward");

        // incoming_sources is no longer a shared table. The worker's IncomingSource entity and
        // its repository were dead code — nothing in the worker injected either, because the
        // Forward path resolves a Destination directly and never loads a Source — and they were
        // deleted rather than kept in step. That also removed a trap this list used to carry:
        // the worker mapped the Source's encrypted HMAC secret without the key version it was
        // encrypted under, so the first worker-side decryptWithFallback for a Source would have
        // used the wrong one.
    }

    private static void exempt(String tableAndColumn, String reason) {
        String previous = DELIBERATELY_UNMAPPED.put(tableAndColumn, reason);
        if (previous != null) {
            throw new IllegalStateException("duplicate exemption for " + tableAndColumn);
        }
    }

    private static final CamelCaseToUnderscoresNamingStrategy NAMING =
            new CamelCaseToUnderscoresNamingStrategy();

    private static final Path REPO_ROOT = locateRepoRoot();

    private static final Path MIGRATIONS =
            REPO_ROOT.resolve("railhook-api/src/main/resources/db/migration");

    private static final Path API_ENTITY_DIR = REPO_ROOT.resolve(
            "railhook-api/src/main/java/com/webhook/platform/api/domain/entity");

    private static final Path WORKER_ENTITY_DIR = REPO_ROOT.resolve(
            "railhook-worker/src/main/java/com/webhook/platform/worker/domain/entity");

    private static final String API = "api";
    private static final String WORKER = "worker";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("parity")
                    .withUsername("parity")
                    .withPassword("parity");

    /** table name -> its columns, straight out of {@code information_schema}. */
    private static Map<String, Set<String>> schema;

    /** simple class name -> the two modules' parsed mappings, for entities both modules declare. */
    private static Map<String, Map<String, EntitySource>> sharedEntities;

    @BeforeAll
    static void migrateAndParse() throws SQLException {
        schema = migrateAndReadSchema();
        sharedEntities = loadSharedEntities();
    }

    // ------------------------------------------------------------------ the ratchet

    @Test
    @DisplayName("every column of a shared table is mapped by both modules, or documented")
    void sharedTableColumnsAreMappedByBothModules() {
        List<String> gaps = new ArrayList<>();

        for (Map.Entry<String, Map<String, EntitySource>> entry : sharedEntities.entrySet()) {
            String entityName = entry.getKey();
            EntitySource api = entry.getValue().get(API);
            EntitySource worker = entry.getValue().get(WORKER);

            assertEquals(api.table(), worker.table(),
                    "The two " + entityName + " entities name different tables ("
                            + api.table() + " vs " + worker.table() + "). One of them is mapping "
                            + "something it does not think it is mapping.");

            String table = api.table();
            Set<String> columns = schema.get(table);
            assertTrue(columns != null && !columns.isEmpty(),
                    "Table '" + table + "' is mapped by both modules but the migrated schema has "
                            + "no such table. Known tables: " + schema.keySet());

            for (String column : columns) {
                boolean inApi = api.columns().contains(column);
                boolean inWorker = worker.columns().contains(column);
                if (inApi && inWorker) {
                    continue;
                }
                String key = table + "." + column;
                if (DELIBERATELY_UNMAPPED.containsKey(key)) {
                    continue;
                }
                String missing = !inApi && !inWorker ? "neither module" : (inApi ? WORKER : API);
                gaps.add(String.format("  %-46s unmapped by %s (%s)", key, missing,
                        !inApi && !inWorker ? "both entities" : entityName));
            }
        }

        assertTrue(gaps.isEmpty(),
                "Columns of a table both modules map are missing from an @Entity. Hibernate's "
                        + "schema validation cannot see this — a missing mapping is silent, which "
                        + "is how events.payload_compressed and endpoints.deleted_at each shipped "
                        + "a production bug.\n\n"
                        + "Map the column in the entity below, or — if the omission is deliberate "
                        + "— add it to DELIBERATELY_UNMAPPED with a reason someone else can check.\n\n"
                        + String.join("\n", gaps) + "\n");
    }

    @Test
    @DisplayName("the exemption list has no stale entries")
    void exemptionsAreAllStillUnmapped() {
        Set<String> stillUnmapped = new TreeSet<>();
        Set<String> knownTables = new TreeSet<>();

        for (Map<String, EntitySource> pair : sharedEntities.values()) {
            EntitySource api = pair.get(API);
            EntitySource worker = pair.get(WORKER);
            String table = api.table();
            knownTables.add(table);
            for (String column : schema.getOrDefault(table, Set.of())) {
                if (!api.columns().contains(column) || !worker.columns().contains(column)) {
                    stillUnmapped.add(table + "." + column);
                }
            }
        }

        Set<String> stale = new TreeSet<>(DELIBERATELY_UNMAPPED.keySet());
        stale.removeAll(stillUnmapped);
        assertEquals(Set.of(), stale,
                "These exemptions no longer describe anything: the column was mapped, renamed or "
                        + "dropped, or its table is no longer shared. Drop them so the list keeps "
                        + "meaning something. Shared tables today: " + knownTables);
    }

    @Test
    @DisplayName("every exemption states a reason, and names a table both modules map")
    void exemptionsAreWellFormed() {
        Set<String> sharedTables = new TreeSet<>();
        sharedEntities.values().forEach(pair -> sharedTables.add(pair.get(API).table()));

        List<String> malformed = new ArrayList<>();
        DELIBERATELY_UNMAPPED.forEach((key, reason) -> {
            int dot = key.lastIndexOf('.');
            if (dot <= 0 || dot == key.length() - 1) {
                malformed.add("  '" + key + "' is not of the form table.column");
                return;
            }
            String table = key.substring(0, dot);
            if (!sharedTables.contains(table)) {
                malformed.add("  '" + key + "' names '" + table + "', which is not a shared table");
            }
            // A reason is the entire value of this list. "n/a", "TODO" and "" are not reasons.
            if (reason == null || reason.strip().length() < 20) {
                malformed.add("  '" + key + "' has no usable reason: \"" + reason + "\"");
            }
        });

        assertTrue(malformed.isEmpty(),
                "An exemption is a claim someone else has to be able to check. Give each one a "
                        + "real reason and a real table.column:\n" + String.join("\n", malformed) + "\n");
    }

    // ------------------------------------------------------- guards on the inputs

    @Test
    @DisplayName("the shared-entity set is discovered from the filesystem and is not empty")
    void sharedEntitySetIsDiscovered() {
        // Eight, not the nine originally recorded: the worker's IncomingSource entity
        // and repository were dead code and were deleted, so incoming_sources stopped being a
        // shared table. Lower this number only for a deletion you can name — the guard exists so
        // a broken directory scan cannot quietly make the whole test vacuous.
        assertTrue(sharedEntities.size() >= 8,
                "Only " + sharedEntities.size() + " entities were found in both modules. This test "
                        + "expects eight. Fewer means the directory scan is broken and this whole "
                        + "test is vacuous. Found: " + sharedEntities.keySet());

        for (Map.Entry<String, Map<String, EntitySource>> entry : sharedEntities.entrySet()) {
            for (Map.Entry<String, EntitySource> side : entry.getValue().entrySet()) {
                EntitySource parsed = side.getValue();
                assertTrue(parsed.columns().size() >= 4,
                        side.getKey() + "'s " + entry.getKey() + " parsed to only "
                                + parsed.columns().size() + " columns " + parsed.columns()
                                + " — the parser, not the entity, is almost certainly wrong.");
            }
        }
    }

    @Test
    @DisplayName("the entity parser never guesses: no unhandled JPA construct")
    void parserHandlesEverythingItSaw() {
        List<String> unhandled = new ArrayList<>();
        for (Map<String, EntitySource> pair : sharedEntities.values()) {
            for (EntitySource parsed : pair.values()) {
                parsed.unhandled().forEach(u -> unhandled.add("  " + parsed.origin() + ": " + u));
            }
        }
        assertTrue(unhandled.isEmpty(),
                "The entity source parser met a JPA construct it was not written for. It "
                        + "deliberately refuses to guess a column name, because a wrong guess "
                        + "would hide drift rather than report it. Teach the parser this "
                        + "construct:\n" + String.join("\n", unhandled) + "\n");
    }

    @Test
    @DisplayName("every column either side maps exists in the migrated schema")
    void parsedMappingsResolveToRealColumns() {
        List<String> phantom = new ArrayList<>();
        for (Map<String, EntitySource> pair : sharedEntities.values()) {
            for (EntitySource parsed : pair.values()) {
                Set<String> columns = schema.getOrDefault(parsed.table(), Set.of());
                for (String mapped : parsed.columns()) {
                    if (!columns.contains(mapped)) {
                        phantom.add("  " + parsed.origin() + " maps " + parsed.table() + "."
                                + mapped + ", which the schema does not have");
                    }
                }
            }
        }
        assertTrue(phantom.isEmpty(),
                "A parsed mapping names a column the schema does not have. Both modules run "
                        + "ddl-auto: validate, so a real entity in this state could not boot — "
                        + "which means this is a bug in the parser's column-name derivation (or, "
                        + "less likely, a genuinely broken entity):\n"
                        + String.join("\n", phantom) + "\n");
    }

    @Test
    @DisplayName("the source parser agrees with reflection on the api entities")
    void sourceParserAgreesWithReflection() {
        List<String> disagreements = new ArrayList<>();
        for (Map.Entry<String, Map<String, EntitySource>> entry : sharedEntities.entrySet()) {
            EntitySource parsed = entry.getValue().get(API);
            Class<?> clazz;
            try {
                clazz = Class.forName(
                        "com.webhook.platform.api.domain.entity." + entry.getKey());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "api entity source exists but its class does not: " + entry.getKey(), e);
            }
            Set<String> reflected = columnsByReflection(clazz);
            if (!reflected.equals(parsed.columns())) {
                Set<String> onlyReflection = new TreeSet<>(reflected);
                onlyReflection.removeAll(parsed.columns());
                Set<String> onlySource = new TreeSet<>(parsed.columns());
                onlySource.removeAll(reflected);
                disagreements.add("  " + entry.getKey()
                        + ": reflection-only=" + onlyReflection + " source-only=" + onlySource);
            }
        }
        assertTrue(disagreements.isEmpty(),
                "The entity source parser disagrees with reflection over the same api classes. "
                        + "Reflection is the reference; fix the parser before trusting what it "
                        + "says about the worker's entities, which are not on this classpath:\n"
                        + String.join("\n", disagreements) + "\n");
    }

    // ------------------------------------------------------------------ schema

    private static Map<String, Set<String>> migrateAndReadSchema() throws SQLException {
        assertTrue(Files.isDirectory(MIGRATIONS),
                "migration directory not found: " + MIGRATIONS.toAbsolutePath());

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + MIGRATIONS.toAbsolutePath())
                .load()
                .migrate();

        Map<String, Set<String>> found = new TreeMap<>();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT c.table_name, c.column_name "
                             + "FROM information_schema.columns c "
                             + "JOIN information_schema.tables t "
                             + "  ON t.table_schema = c.table_schema AND t.table_name = c.table_name "
                             + "WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE' "
                             + "ORDER BY c.table_name, c.ordinal_position")) {
            while (rows.next()) {
                found.computeIfAbsent(rows.getString(1), t -> new LinkedHashSet<>())
                        .add(rows.getString(2));
            }
        }
        assertTrue(found.size() > 20,
                "Only " + found.size() + " tables after migrating — Flyway did not run the real "
                        + "schema, so every comparison below would be vacuous.");
        return found;
    }

    // ------------------------------------------------------------------ entities

    private static Map<String, Map<String, EntitySource>> loadSharedEntities() {
        Set<String> apiNames = entityClassNames(API_ENTITY_DIR);
        Set<String> workerNames = entityClassNames(WORKER_ENTITY_DIR);

        Set<String> shared = new TreeSet<>(apiNames);
        shared.retainAll(workerNames);

        Map<String, Map<String, EntitySource>> loaded = new LinkedHashMap<>();
        for (String name : shared) {
            Map<String, EntitySource> pair = new LinkedHashMap<>();
            pair.put(API, EntitySource.parse(API_ENTITY_DIR.resolve(name + ".java"), API));
            pair.put(WORKER, EntitySource.parse(WORKER_ENTITY_DIR.resolve(name + ".java"), WORKER));
            loaded.put(name, pair);
        }
        return loaded;
    }

    /** Names of the {@code @Entity} classes in a package directory. */
    private static Set<String> entityClassNames(Path dir) {
        assertTrue(Files.isDirectory(dir), "entity directory not found: " + dir.toAbsolutePath());
        try (Stream<Path> files = Files.list(dir)) {
            Set<String> names = new TreeSet<>();
            for (Path file : files.sorted().toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (EntitySource.stripComments(source).contains("@Entity")) {
                    names.add(fileName.substring(0, fileName.length() - ".java".length()));
                }
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + dir, e);
        }
    }

    /** The reference implementation the source parser is checked against, for api classes only. */
    private static Set<String> columnsByReflection(Class<?> entity) {
        Set<String> columns = new TreeSet<>();
        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (field.isAnnotationPresent(jakarta.persistence.Transient.class)) {
                continue;
            }
            if (field.isAnnotationPresent(jakarta.persistence.OneToMany.class)
                    || field.isAnnotationPresent(jakarta.persistence.ManyToMany.class)
                    || field.isAnnotationPresent(jakarta.persistence.ElementCollection.class)) {
                continue;
            }
            jakarta.persistence.JoinColumn join =
                    field.getAnnotation(jakarta.persistence.JoinColumn.class);
            if (join != null) {
                columns.add(join.name());
                continue;
            }
            jakarta.persistence.Column column =
                    field.getAnnotation(jakarta.persistence.Column.class);
            if (column != null && !column.name().isEmpty()) {
                columns.add(column.name());
                continue;
            }
            columns.add(implicitColumnName(field.getName()));
        }
        return columns;
    }

    /**
     * The column Hibernate derives for a property with no explicit name, using the real
     * {@link CamelCaseToUnderscoresNamingStrategy} rather than a re-implementation of it. That is
     * Spring Boot 3's default physical naming strategy and neither module's {@code application.yml}
     * overrides it. The strategy ignores the {@code JdbcEnvironment} it is handed, so {@code null}
     * is safe — and if a future Hibernate stops ignoring it, this throws rather than drifting.
     */
    private static String implicitColumnName(String propertyName) {
        return NAMING.toPhysicalColumnName(Identifier.toIdentifier(propertyName), null).getText();
    }

    private static Path locateRepoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && candidate != null; i++) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("railhook-api"))
                    && Files.isDirectory(candidate.resolve("railhook-worker"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "could not find the reactor root from " + Path.of("").toAbsolutePath());
    }

    // ---------------------------------------------------------- the source parser

    /**
     * One module's {@code @Entity} class, as read off its source file.
     *
     * @param origin  human-readable "module's ClassName", for failure messages
     * @param table   the physical table the entity maps
     * @param columns every column the entity maps
     * @param unhandled constructs the parser refused to interpret rather than guess at
     */
    private record EntitySource(String origin, String table, Set<String> columns,
                                List<String> unhandled) {

        static EntitySource parse(Path file, String module) {
            String source;
            try {
                source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + file, e);
            }
            String className = file.getFileName().toString().replace(".java", "");
            String origin = module + "'s " + className;

            List<String> unhandled = new ArrayList<>();
            Set<String> columns = new TreeSet<>();

            String table = explicitTableName(source)
                    .orElseGet(() -> implicitColumnName(className));

            for (Member member : members(classBody(source, className, origin))) {
                resolve(member, columns, unhandled, origin);
            }
            return new EntitySource(origin, table, columns, unhandled);
        }

        private static void resolve(Member member, Set<String> columns, List<String> unhandled,
                                    String origin) {
            if (member.isStatic()) {
                return;
            }
            String annotations = member.annotations();
            if (has(annotations, "Transient")) {
                return;
            }
            // Mapped on the other table, or on a join table: no column here.
            if (has(annotations, "OneToMany") || has(annotations, "ManyToMany")
                    || has(annotations, "ElementCollection")) {
                return;
            }
            for (String construct : List.of("Embedded", "EmbeddedId", "AttributeOverride",
                    "JoinColumns", "MapsId", "Any")) {
                if (has(annotations, construct)) {
                    unhandled.add("@" + construct + " on field '" + member.name() + "'");
                    return;
                }
            }
            if (member.name().contains(",")) {
                unhandled.add("multi-name field declaration '" + member.name() + "'");
                return;
            }

            String joinColumn = attribute(annotations, "JoinColumn", "name");
            if (joinColumn != null) {
                columns.add(joinColumn);
                return;
            }
            if (has(annotations, "JoinColumn")) {
                unhandled.add("@JoinColumn with no name on field '" + member.name() + "'");
                return;
            }
            if (has(annotations, "ManyToOne") || has(annotations, "OneToOne")) {
                unhandled.add("association on field '" + member.name()
                        + "' with no @JoinColumn — its implicit foreign-key name is not derived here");
                return;
            }

            String explicit = attribute(annotations, "Column", "name");
            columns.add(explicit != null ? explicit : implicitColumnName(member.name()));
        }

        /** {@code @Table(name = "x")}, if the class declares one. */
        private static java.util.Optional<String> explicitTableName(String source) {
            String value = attribute(source, "Table", "name");
            return java.util.Optional.ofNullable(value);
        }

        /** Text between the class declaration's opening brace and its matching close. */
        private static String classBody(String source, String className, String origin) {
            int declaration = source.indexOf("class " + className);
            assertTrue(declaration >= 0, "could not find 'class " + className + "' in " + origin);
            int open = source.indexOf('{', declaration);
            assertTrue(open >= 0, "could not find the class body of " + origin);
            int close = matching(source, open, '{', '}');
            assertTrue(close > open, "unbalanced braces in " + origin);
            return source.substring(open + 1, close);
        }

        /**
         * Splits a class body into its top-level members.
         *
         * <p>Walks the body tracking paren and brace depth. A {@code ;} at depth zero ends a field
         * declaration; a {@code {} at depth zero starts a method, nested type or initialiser, whose
         * whole block is skipped. Braces and semicolons inside an annotation's argument list — the
         * {@code indexes = &#123;@Index(...)&#125;} form — sit at paren depth &gt; 0 and are
         * therefore not mistaken for member boundaries.
         */
        private static List<Member> members(String body) {
            List<Member> members = new ArrayList<>();
            StringBuilder buffer = new StringBuilder();
            int parens = 0;
            int i = 0;
            while (i < body.length()) {
                char c = body.charAt(i);
                if (c == '"') {
                    int end = endOfStringLiteral(body, i);
                    buffer.append(body, i, end);
                    i = end;
                    continue;
                }
                if (c == '(') {
                    parens++;
                } else if (c == ')') {
                    parens--;
                } else if (parens == 0 && c == '{') {
                    int close = matching(body, i, '{', '}');
                    buffer.setLength(0);
                    i = close + 1;
                    continue;
                } else if (parens == 0 && c == ';') {
                    Member member = Member.of(buffer.toString());
                    if (member != null) {
                        members.add(member);
                    }
                    buffer.setLength(0);
                    i++;
                    continue;
                }
                buffer.append(c);
                i++;
            }
            return members;
        }

        private static boolean has(String annotations, String simpleName) {
            return annotationStart(annotations, simpleName) >= 0;
        }

        /**
         * The {@code String} value of one attribute of one annotation, e.g. {@code name} of
         * {@code @Column}. Returns null when the annotation or the attribute is absent.
         */
        private static String attribute(String text, String annotation, String attribute) {
            int start = annotationStart(text, annotation);
            if (start < 0) {
                return null;
            }
            int paren = start + annotation.length() + 1;
            while (paren < text.length() && Character.isWhitespace(text.charAt(paren))) {
                paren++;
            }
            if (paren >= text.length() || text.charAt(paren) != '(') {
                return null;
            }
            String args = text.substring(paren + 1, matching(text, paren, '(', ')'));
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?:^|[,(\\s])" + attribute + "\\s*=\\s*\"([^\"]*)\"")
                    .matcher(args);
            return matcher.find() ? matcher.group(1) : null;
        }

        /** Index of {@code @Name} in {@code text}, as a whole annotation name. */
        private static int annotationStart(String text, String simpleName) {
            int from = 0;
            while (true) {
                int at = text.indexOf("@" + simpleName, from);
                if (at < 0) {
                    return -1;
                }
                int after = at + 1 + simpleName.length();
                boolean wholeName = after >= text.length()
                        || !(Character.isJavaIdentifierPart(text.charAt(after))
                             || text.charAt(after) == '.');
                if (wholeName) {
                    return at;
                }
                from = at + 1;
            }
        }

        private static int matching(String text, int open, char opener, char closer) {
            int depth = 0;
            int i = open;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '"') {
                    i = endOfStringLiteral(text, i);
                    continue;
                }
                if (c == '\'') {
                    i = endOfCharLiteral(text, i);
                    continue;
                }
                if (c == opener) {
                    depth++;
                } else if (c == closer) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
                i++;
            }
            return -1;
        }

        private static int endOfStringLiteral(String text, int quote) {
            int i = quote + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return i + 1;
                }
                i++;
            }
            return text.length();
        }

        private static int endOfCharLiteral(String text, int quote) {
            int i = quote + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    return i + 1;
                }
                i++;
            }
            return text.length();
        }

        /**
         * Removes comments, keeping string literals intact. Essential rather than cosmetic: these
         * entities carry Javadoc that names columns in {@code &#123;@code ...&#125;} and
         * {@code &#123;@link ...&#125;} tags, which an annotation scan would otherwise read as
         * annotations.
         */
        static String stripComments(String source) {
            StringBuilder out = new StringBuilder(source.length());
            int i = 0;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (c == '"') {
                    int end = endOfStringLiteral(source, i);
                    out.append(source, i, end);
                    i = end;
                    continue;
                }
                if (c == '\'') {
                    int end = endOfCharLiteral(source, i);
                    out.append(source, i, end);
                    i = end;
                    continue;
                }
                if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    while (i < source.length() && source.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                    int end = source.indexOf("*/", i + 2);
                    i = end < 0 ? source.length() : end + 2;
                    out.append('\n');
                    continue;
                }
                out.append(c);
                i++;
            }
            return out.toString();
        }
    }

    /** One field declaration: its annotations, and the declaration itself. */
    private record Member(String annotations, String declaration, String name, boolean isStatic) {

        /**
         * Splits {@code text} — everything since the previous member boundary — into leading
         * annotations and the declaration they sit on. Returns null when what is left is not a
         * field (an {@code import}, a package statement, an enum constant list, a stray
         * {@code ;}).
         */
        static Member of(String text) {
            int i = 0;
            int annotationsEnd = 0;
            while (i < text.length()) {
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    i++;
                }
                if (i >= text.length() || text.charAt(i) != '@') {
                    break;
                }
                i++;
                while (i < text.length() && (Character.isJavaIdentifierPart(text.charAt(i))
                        || text.charAt(i) == '.')) {
                    i++;
                }
                int j = i;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j < text.length() && text.charAt(j) == '(') {
                    int close = EntitySource.matching(text, j, '(', ')');
                    i = close < 0 ? text.length() : close + 1;
                }
                annotationsEnd = i;
            }

            String annotations = text.substring(0, annotationsEnd);
            String declaration = text.substring(annotationsEnd).trim();
            if (declaration.isEmpty()) {
                return null;
            }
            // Drop any initialiser: `= VerificationStatus.SKIPPED`, `= new HashMap<>()`.
            int assign = declaration.indexOf('=');
            String signature = (assign < 0 ? declaration : declaration.substring(0, assign)).trim();

            List<String> tokens = new ArrayList<>(List.of(signature.split("\\s+")));
            tokens.removeIf(String::isBlank);
            // `Type name` is the shortest a field can be; anything shorter is not one.
            if (tokens.size() < 2) {
                return null;
            }
            String name = tokens.get(tokens.size() - 1);
            if (!name.chars().allMatch(ch -> Character.isJavaIdentifierPart(ch) || ch == ',')) {
                return null;
            }
            boolean isStatic = tokens.contains("static");
            return new Member(annotations, declaration, name, isStatic);
        }
    }
}
