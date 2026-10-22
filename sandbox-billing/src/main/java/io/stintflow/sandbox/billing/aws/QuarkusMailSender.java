package io.stintflow.sandbox.billing.aws;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.stintflow.sandbox.billing.worker.BillingPorts.MailSender;

/** SDD S.2, 8e: SMTP through quarkus-mailer (MailHog in the sandbox). */
public final class QuarkusMailSender implements MailSender {

    private final Mailer mailer;

    public QuarkusMailSender(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void send(String from, String to, String subject, String body) {
        mailer.send(Mail.withText(to, subject, body).setFrom(from));
    }
}
