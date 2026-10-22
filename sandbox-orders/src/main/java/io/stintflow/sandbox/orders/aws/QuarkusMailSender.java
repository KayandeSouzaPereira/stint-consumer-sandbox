package io.stintflow.sandbox.orders.aws;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.stintflow.sandbox.orders.worker.OrdersPorts.Attachment;
import io.stintflow.sandbox.orders.worker.OrdersPorts.MailSender;

/** SDD S.3, 8e: SMTP through quarkus-mailer (MailHog in the sandbox). */
public final class QuarkusMailSender implements MailSender {

    private final Mailer mailer;

    public QuarkusMailSender(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void send(String from, String to, String subject, String body, Attachment attachment) {
        Mail mail = Mail.withText(to, subject, body).setFrom(from);
        if (attachment != null) {
            mail.addAttachment(attachment.name(), attachment.content(), attachment.contentType());
        }
        mailer.send(mail);
    }
}
