package me.lendev.flow.mail.transport;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailModels.RecipientResult;
import me.lendev.flow.mail.MailModels.Status;
import me.lendev.flow.mail.MailModels.Submission;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将 SMTP 明确回复和提交阶段转换为脱敏的发送结果。
 */
final class SmtpSubmission {

    private SmtpSubmission() {
    }

    static Submission failure(List<Recipient> recipients, MessagingException failure, boolean commitStarted) {
        Map<String, Status> confirmed = new HashMap<>();
        if (failure instanceof SendFailedException sendFailure) {
            recordAddresses(confirmed, sendFailure.getInvalidAddresses(), Status.FAILED);
        }
        for (Exception item = failure; item instanceof MessagingException mailException; item = mailException.getNextException()) {
            if (item instanceof SMTPAddressFailedException addressFailure && rejected(addressFailure.getReturnCode())) {
                recordAddress(confirmed, addressFailure.getAddress(), Status.FAILED);
            }
        }

        int responseCode = failure instanceof SMTPSendFailedException smtp ? smtp.getReturnCode() : -1;
        if (responseCode == 250 && failure instanceof SendFailedException sendFailure) {
            recordAddresses(confirmed, sendFailure.getValidSentAddresses(), Status.ACCEPTED);
        }
        List<RecipientResult> results = recipients.stream()
                .filter(recipient -> confirmed.containsKey(addressKey(recipient.address())))
                .map(recipient -> new RecipientResult(recipient.address(), confirmed.get(addressKey(recipient.address()))))
                .toList();
        if (results.stream().anyMatch(recipient -> recipient.status() == Status.ACCEPTED)) {
            return new Submission(Status.PARTIAL, null, "MAIL_SMTP_PARTIAL", "SMTP 服务器受理了部分收件人", results);
        }
        if (!commitStarted || rejected(responseCode)) {
            return new Submission(Status.FAILED, null, "MAIL_SMTP_REJECTED", "SMTP 邮件提交失败", results);
        }
        return new Submission(Status.UNKNOWN, null, "MAIL_SMTP_SUBMISSION_UNKNOWN", "无法确认 SMTP 服务器是否受理邮件", results);
    }

    private static boolean rejected(int responseCode) {
        return responseCode >= 400 && responseCode < 600;
    }

    private static void recordAddresses(Map<String, Status> results, Address[] addresses, Status status) {
        if (addresses != null) {
            for (Address address : addresses) {
                recordAddress(results, address, status);
            }
        }
    }

    private static void recordAddress(Map<String, Status> results, Address address, Status status) {
        if (address instanceof InternetAddress mailAddress) {
            results.put(addressKey(mailAddress.getAddress()), status);
        }
    }

    private static String addressKey(String address) {
        return address.toLowerCase(Locale.ROOT);
    }
}
