package com.webhook.platform.common.transform;

import java.util.List;

/**
 * Worked examples of the template language, shared by {@code TemplateLanguageParityTest} in api
 * and in worker. The two implementations stay separate on purpose: on a malformed path the api
 * preview substitutes null, while the worker throws so a signed body is never silently wrong.
 * This corpus catches drift on everything else. Expected values are compared as parsed JSON.
 */
public final class TemplateLanguageConformance {

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

                // Both implementations mean to write "" here, but the evaluator returns a JSON null
                // node, not a Java null, so the fallback never runs. Pinned as it behaves because
                // receivers parse this today.
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
