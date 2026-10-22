package io.stintflow.sandbox.privacy.filter;

import java.util.Map;

import org.jboss.logging.Logger;

import io.stintflow.worker.redaction.RedactionLayer;

/**
 * SDD S.6, 8a/8b (layer 4): asks a local model whether personal data is left in the text the earlier layers produced.
 * It never changes the text and can only add doubt, never remove it: it is never the only layer. At or above the
 * threshold the redaction is in doubt; a model that fails, times out or answers nonsense is in doubt too (fail closed).
 * Logs carry the verdict and the number, never the text.
 */
public final class LlmCheckLayer implements RedactionLayer {

    private static final Logger LOG = Logger.getLogger(LlmCheckLayer.class);
    public static final double DEFAULT_THRESHOLD = 0.5;

    private final PersonalDataCheck check;
    private final double threshold;

    public LlmCheckLayer(PersonalDataCheck check, double threshold) {
        this.check = check;
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return "noul";
    }

    @Override
    public Result apply(String text, Scope scope) {
        try {
            double likelihood = check.likelihood(text);
            if (Double.isNaN(likelihood) || likelihood < 0 || likelihood > 1) {
                LOG.warnf("The personal-data check answered %s: out of range, in doubt", likelihood);
                return new Result(text, Map.of(), true);
            }
            boolean doubt = likelihood >= threshold;
            LOG.debugf("Personal-data check: %.3f (threshold %.2f)%s", likelihood, threshold, doubt ? ", in doubt" : "");
            return new Result(text, Map.of(), doubt);
        } catch (Exception e) {
            LOG.warnf("The personal-data check failed (%s): in doubt", e.getClass().getSimpleName());
            return new Result(text, Map.of(), true);
        }
    }
}
