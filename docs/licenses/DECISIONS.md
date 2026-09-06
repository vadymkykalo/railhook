# License decisions

Hookflow itself is [MIT](../../LICENSE). This records the license questions an evaluating company's legal team is
most likely to raise, plus how they were checked.

## MinIO was AGPL-3.0 — decision: removed, because nothing ever used it

`docker-compose.yml` used to carry a `minio` service behind its own Compose
profile: the only AGPL-3.0-licensed component anywhere in this repo's
dependency graph. It was kept on the argument that it was opt-in, undeployed
by default, and reserved for future object-storage work.

That future never arrived. Two years on, no application code referenced it —
no `minio`, `S3Client`, `amazonaws` or `software.amazon` import in any module,
and no object-storage dependency in any `pom.xml`. The only hit outside the
Compose file was `ProductionSafetyValidator` checking a password for a service
that nothing connected to. What it did carry was a pinned
`RELEASE.2024-01-16` image, ageing into CVE-scanner findings on a service the
product could not use, and a licensing question every evaluating legal team
had to be walked through for no benefit.

**Decision:** removed from `docker-compose.yml`, `.env.dist`, the `Makefile`,
`NOTICE` and `ProductionSafetyValidator`. Hookflow's dependency graph now
contains no AGPL/GPL/SSPL/BSL component at all, which is a simpler and more
honest answer than the one this section used to give.

When object storage is actually built, the integration should target the S3
API rather than MinIO specifically, so an operator can point it at MinIO,
[SeaweedFS](https://github.com/seaweedfs/seaweedfs) (Apache-2.0), or any
hosted bucket (AWS S3, Cloudflare R2, Backblaze B2). The AGPL obligation is
then a choice the operator makes, not one the default distribution ships.

## Bitnami subcharts (Helm chart) — removed, not pinned

`deploy/helm/hookflow/Chart.yaml` used to declare three optional subchart
dependencies from `https://charts.bitnami.com/bitnami` — postgresql 12.x.x,
redis 18.x.x and kafka 26.x.x. It now declares `dependencies: []`.

License was never the problem: all three are Apache-2.0, wrapping upstream
PostgreSQL/Redis/Kafka. Availability was. Bitnami moved its free catalog to a
restricted "Legacy" tier on 2025-08-28, images referenced by those chart
versions stopped receiving updates, Kafka was dropped from the catalog
outright, and the AWS-hosted mirror retires 2026-06-10. Re-pinning would have
been a bet on a catalog that is actively shrinking.

The chart therefore requires bring-your-own PostgreSQL/Kafka/Redis — see the
`external:` blocks in `values.yaml` and the Helm README's prerequisites, which
is how production deployments were documented to work anyway.
`docker-compose.yml` names the exact images this project is tested against
(postgres:16-alpine, apache/kafka:3.7.0, redis:7-alpine — none of them
Bitnami) as a reference for a self-managed in-cluster deployment.

There is consequently no third-party license exception to record here for the
Helm chart at all.

## How this was verified (not just asserted)

```bash
# Backend: 230 direct+transitive Maven dependencies scanned
mvn org.codehaus.mojo:license-maven-plugin:2.4.0:aggregate-add-third-party -DskipTests
# -> target/generated-sources/license/THIRD-PARTY.txt (copied here as backend-THIRD-PARTY.txt)
grep -iE "gpl|agpl|affero|sspl|commons clause|bsl|business source" backend-THIRD-PARTY.txt
# -> only "GPL2 w/ CPE" hits (Jakarta EE's standard Classpath-Exception dual license,
#    not copyleft in practice) and one Apache-2.0/LGPL dual-licensed dependency
#    (JNA) where the permissive option applies. No AGPL/SSPL/BSL anywhere.

# Backend SBOM (CycloneDX 1.5, 191 components)
mvn org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom -DskipTests -DoutputFormat=json -DoutputName=sbom
# -> target/sbom.json (copied here as backend-sbom.json)

# Frontend: 654 npm packages scanned
cd webhook-platform-ui && npm ci
npx license-checker --summary
# -> 563 MIT, 49 ISC, 14 Apache-2.0, 10 BSD-2-Clause, 9 BSD-3-Clause, 2 MPL-2.0,
#    1 MIT-0, 1 Python-2.0, 1 CC-BY-4.0, 1 0BSD, 1 (MIT OR CC0-1.0), 1 MIT AND ISC
#    — no copyleft.
npx license-checker --json --excludePrivatePackages > frontend-licenses.json

# Frontend SBOM (CycloneDX 1.6, 619 components)
npx @cyclonedx/cyclonedx-npm --output-format json --output-file frontend-sbom.json
```

These are real, one-time-generated reports as of 2026-08-22 (commit at the
time this task landed) — see `README.md` in this directory for how to keep
them current.
