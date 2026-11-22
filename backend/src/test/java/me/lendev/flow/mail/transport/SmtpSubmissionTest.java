package me.lendev.flow.mail.transport;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.RecipientResult;
import me.lendev.flow.mail.MailModels.Status;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpSubmissionTest {

    private static final List<Recipient> RECIPIENTS = List.of(new Recipient("accepted@example.com", null),
            new Recipient("rejected@example.com", null), new Recipient("unconfirmed@example.com", null));

    @Test
    void keepsExplicitTransientRecipientRejectionAfterPartialAcceptance() throws Exception {
        var accepted = new InternetAddress("accepted@example.com");
        var rejected = new InternetAddress("rejected@example.com");
        var unconfirmed = new InternetAddress("unconfirmed@example.com");
        var rejectedAddress = new SMTPAddressFailedException(rejected, "RCPT TO", 450, "private recipient error");
        var partial = new SMTPSendFailedException(".", 250, "private provider message", rejectedAddress,
                new Address[]{accepted}, new Address[]{rejected, unconfirmed}, null);

        var result = SmtpSubmission.failure(RECIPIENTS, partial, true);

        assertThat(result.status()).isEqualTo(Status.PARTIAL);
        assertThat(result.recipientResults()).containsExactly(new RecipientResult("accepted@example.com", Status.ACCEPTED),
                new RecipientResult("rejected@example.com", Status.FAILED));
        assertThat(result.errorMessage()).doesNotContain("private", "rejected@example.com");
    }

    @Test
    void leavesUnacknowledgedAcceptanceUnknownAfterSendingTheMessageTerminator() {
        var timeout = new MessagingException("wrote private message", new SocketTimeoutException());
        var before = SmtpSubmission.failure(RECIPIENTS, timeout, false);
        var after = SmtpSubmission.failure(RECIPIENTS, timeout, true);

        assertThat(before.status()).isEqualTo(Status.FAILED);
        assertThat(after.status()).isEqualTo(Status.UNKNOWN);
        assertThat(after.recipientResults()).isEmpty();
        assertThat(after.errorMessage()).doesNotContain("private message");
    }

    @Test
    void recordsDefinitiveEndOfDataRejectionWithNoIndividualResult() throws Exception {
        var acceptedEnvelope = new InternetAddress("accepted@example.com");
        var rejectedData = new SMTPSendFailedException(".", 554, "private message body", null,
                null, new Address[]{acceptedEnvelope}, null);

        var result = SmtpSubmission.failure(RECIPIENTS, rejectedData, true);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.recipientResults()).isEmpty();
    }

    @Test
    void recordsInvalidRecipientWithoutAssumingThatOtherAddressesWereRejected() throws Exception {
        var valid = new InternetAddress("accepted@example.com");
        var invalid = new InternetAddress("rejected@example.com");
        var rejected = new SendFailedException("private addresses", null, null, new Address[]{valid}, new Address[]{invalid});

        var result = SmtpSubmission.failure(RECIPIENTS, rejected, false);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.recipientResults()).containsExactly(new RecipientResult("rejected@example.com", Status.FAILED));
    }
}
