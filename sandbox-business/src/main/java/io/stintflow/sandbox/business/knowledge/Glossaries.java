package io.stintflow.sandbox.business.knowledge;

import java.util.List;

import io.stintflow.knowledge.extraction.Glossary;

/** SDD S.12, 8a: the terms each domain's extraction is anchored on, drawn from the synthetic corpus. */
public final class Glossaries {

    private Glossaries() {
    }

    /** What the business conversations name: the people who borrow and what they borrow. */
    public static Glossary business() {
        return new Glossary("business", "1", List.of(
                term("t-member", "Term", "member", "members"),
                term("t-book", "Term", "book", "books"),
                term("t-loan", "Term", "loan", "loans", "borrow"),
                term("t-late-return", "Term", "late return", "late returns"),
                term("t-limit", "Term", "limit", "limit per member")));
    }

    private static Glossary.Term term(String id, String type, String name, String... aliases) {
        return new Glossary.Term(id, type, name, List.of(aliases), null);
    }
}
