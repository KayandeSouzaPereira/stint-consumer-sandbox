package io.stintflow.sandbox.knowledge;

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

    /** What the technical conversations name: the same terms and the pieces of the system that carry them. */
    public static Glossary engineering() {
        return new Glossary("engineering", "1", List.of(
                term("t-member", "Term", "member", "members"),
                term("t-loan", "Term", "loan", "loans", "borrow", "borrowing"),
                term("t-late-return", "Term", "late return", "late returns"),
                term("t-limit", "Term", "limit", "limit per member"),
                term("s-loans", "System", "loans service", "loans"),
                term("s-catalog", "System", "catalog", "catalogue")));
    }

    private static Glossary.Term term(String id, String type, String name, String... aliases) {
        return new Glossary.Term(id, type, name, List.of(aliases), null);
    }
}
