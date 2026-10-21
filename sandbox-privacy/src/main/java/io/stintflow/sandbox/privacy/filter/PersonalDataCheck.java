package io.stintflow.sandbox.privacy.filter;

/**
 * SDD S.6, 8a (layer 4): "is there still personal data in this text?", answered by a local model. The probability is
 * what the model says, in [0, 1]; a failure is an exception, which the layer reads as doubt (fail closed).
 */
@FunctionalInterface
public interface PersonalDataCheck {

    /** How likely it is that {@code text} still holds personal data. */
    double likelihood(String text) throws Exception;
}
