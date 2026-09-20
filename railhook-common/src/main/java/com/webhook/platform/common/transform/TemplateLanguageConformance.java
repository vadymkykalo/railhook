package com.webhook.platform.common.transform;

import java.util.List;

/**
 * What the transformation template language means, written down once as worked examples.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The language has two implementations and they are not going to be merged into one:
 *
 * <ul>
 *   <li>{@code TemplateTransformer} in {@code railhook-api} — the preview a person reads before
 *       saving, and the workflow transform node;</li>
 *   <li>{@code PayloadTransformService} in {@code railhook-worker} — what actually reshapes a
 *       Delivery or a Forward on its way out.</li>
 * </ul>
 *
 * <p>They are duplicated deliberately, and moving the language into this module would not be the
 * small tidy-up it looks like: the two differ on purpose in what a <em>malformed</em> path does.
 * The api swallows it and substitutes null, because a preview must render something for a template
 * somebody is still typing. The worker lets it throw, because a silently nulled field in a
 * delivered, HMAC-signed body is the bug its {@code evaluateJsonPath} javadoc was written about —
 * a transformation is all-or-nothing there. Unifying them would have to pick one of those, which
 * is a change to delivery behaviour rather than a refactor.
 *
 * <p>What was missing was anything that failed when they drifted on everything <em>else</em>. This
 * is that: one corpus of template/payload/expected triples, run against each implementation by a
 * test in its own module ({@code TemplateLanguageParityTest}, in {@code railhook-api} and in
 * {@code railhook-worker}). A single test class cannot do it — the two modules are siblings in the
 * reactor and neither is on the other's classpath — so the corpus is what the two tests share.
 * Add a case here and both modules answer for it.
 *
 * <p>Expected values are JSON text and are compared as parsed trees, so key order and whitespace
 * are not part of the claim; types are.
 */
public final class TemplateLanguageConformance {

    /**
     * One worked example: applying {@link #template} to {@link #payload} produces
     * {@link #expected}, in both implementations.
     *
     * @param name what the case is about, used as the test's display name
     */
    public record Case(String name, String template, String payload, String expected) {
    }

    private TemplateLanguageConformance() {
    }

    public static List<Case> cases() {
        return List.of(
                new Case("a whole value that is one expression keeps the referenced type",
                        "{\"id\":\"${$.id}\",\"count\":\"${$.count}\",\"ok\":\"${$.ok}\"}",
                        "{\"id\":\"evt_1\",\"count\":7,\"ok\":false}",
                        "{\"id\":\"evt_1\",\"count\":7,\"ok\":false}"),

                new Case("an object or an array comes through whole, not stringified",
                        "{\"data\":\"${$.data}\",\"tags\":\"${$.tags}\"}",
                        "{\"data\":{\"k\":\"v\",\"n\":1},\"tags\":[\"a\",\"b\"]}",
                        "{\"data\":{\"k\":\"v\",\"n\":1},\"tags\":[\"a\",\"b\"]}"),

                new Case("an expression inside surrounding text makes a string",
                        "{\"line\":\"order ${$.id} for ${$.amount} ${$.currency}\"}",
                        "{\"id\":\"ord_9\",\"amount\":99.5,\"currency\":\"USD\"}",
                        "{\"line\":\"order ord_9 for 99.5 USD\"}"),

                new Case("a non-string interpolated into text is written as its JSON form",
                        "{\"line\":\"data=${$.data} list=${$.list}\"}",
                        "{\"data\":{\"k\":\"v\"},\"list\":[1,2]}",
                        "{\"line\":\"data={\\\"k\\\":\\\"v\\\"} list=[1,2]\"}"),

                new Case("a path that matches nothing is null, not an error and not the empty string",
                        "{\"missing\":\"${$.nope}\",\"deep\":\"${$.a.b.c}\"}",
                        "{\"a\":{}}",
                        "{\"missing\":null,\"deep\":null}"),

                // Both implementations read "null" here, and both have an unreachable branch that
                // says they meant to write the empty string: the evaluator returns a JSON null
                // node rather than a Java null, so the `value != null ? … : ""` in each never
                // takes its second arm. Pinned as it behaves rather than as it reads, because a
                // receiver is parsing this string today and "fixing" it would change what they get.
                new Case("a path that matches nothing interpolates the text \"null\"",
                        "{\"line\":\"[${$.nope}]\"}",
                        "{\"a\":1}",
                        "{\"line\":\"[null]\"}"),

                new Case("a source field that is present and null stays null",
                        "{\"n\":\"${$.explicitNull}\"}",
                        "{\"explicitNull\":null}",
                        "{\"n\":null}"),

                new Case("nested arrays are walked to the bottom, elements and all",
                        "{\"rows\":[[\"${$.a}\",2],[{\"deep\":\"${$.b[1]}\"},[\"${$.b[0]}\"]]]}",
                        "{\"a\":\"A\",\"b\":[10,20]}",
                        "{\"rows\":[[\"A\",2],[{\"deep\":20},[10]]]}"),

                new Case("a template that is itself an array is applied element by element",
                        "[\"${$.a}\",{\"b\":\"${$.b}\"},3]",
                        "{\"a\":1,\"b\":\"two\"}",
                        "[1,{\"b\":\"two\"},3]"),

                new Case("template literals that are not strings are copied through untouched",
                        "{\"n\":42,\"f\":1.5,\"b\":true,\"z\":null,\"nested\":{\"k\":[1,\"x\",null]}}",
                        "{\"anything\":1}",
                        "{\"n\":42,\"f\":1.5,\"b\":true,\"z\":null,\"nested\":{\"k\":[1,\"x\",null]}}"),

                new Case("text with no expression in it is left alone, dollars and braces included",
                        "{\"s\":\"costs $5, uses { and } and $ { apart\"}",
                        "{\"a\":1}",
                        "{\"s\":\"costs $5, uses { and } and $ { apart\"}"),

                new Case("there is no escape: a backslash before ${ is kept and the expression still resolves",
                        "{\"s\":\"\\\\${$.a}\",\"t\":\"$${$.a}\"}",
                        "{\"a\":\"A\"}",
                        "{\"s\":\"\\\\A\",\"t\":\"$A\"}"),

                new Case("an empty ${} is not an expression and survives as text",
                        "{\"s\":\"${}\",\"t\":\"a ${} b\"}",
                        "{\"a\":1}",
                        "{\"s\":\"${}\",\"t\":\"a ${} b\"}"),

                new Case("the root expression takes the whole payload",
                        "{\"all\":\"${$}\"}",
                        "{\"a\":1,\"b\":[2]}",
                        "{\"all\":{\"a\":1,\"b\":[2]}}"),

                new Case("a path that can match many things yields the list of matches",
                        "{\"names\":\"${$.items[?(@.active == true)].name}\",\"ids\":\"${$..id}\"}",
                        "{\"id\":\"top\",\"items\":[{\"id\":\"i1\",\"name\":\"one\",\"active\":true},"
                                + "{\"id\":\"i2\",\"name\":\"two\",\"active\":false}]}",
                        "{\"names\":[\"one\"],\"ids\":[\"top\",\"i1\",\"i2\"]}"),

                new Case("a key is never an expression — only values are",
                        "{\"${$.a}\":\"${$.a}\"}",
                        "{\"a\":\"A\"}",
                        "{\"${$.a}\":\"A\"}"),

                new Case("an empty template object produces an empty object",
                        "{}",
                        "{\"a\":1}",
                        "{}")
        );
    }
}
