package io.stintflow.sandbox.business.knowledge;

import java.util.Set;

import io.stintflow.knowledge.Embedder;

/**
 * SDD S.12, 8f: the model the Business base moves to in the build. It embeds as the deterministic embedder does under
 * another name, so a move changes the model of the base and nothing about what a question finds (no test calls a real
 * embedding model).
 */
public final class RenamedEmbedder implements Embedder {

    private final Embedder inner;
    private final String model;

    public RenamedEmbedder(Embedder inner, String model) {
        this.inner = inner;
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        return inner.embed(text);
    }

    @Override
    public int dimension() {
        return inner.dimension();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Set<String> languages() {
        return inner.languages();
    }
}
