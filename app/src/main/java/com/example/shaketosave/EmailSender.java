package com.example.shaketosave;

import android.os.AsyncTask;
import java.util.Properties;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class EmailSender extends AsyncTask<Void, Void, Boolean> {

    private final String senderEmail;
    private final String senderPassword;
    private final String recipientEmail;
    private final String subject;
    private final String messageBody;
    private final EmailCallback callback;
    private String errorMessage = "";

    public interface EmailCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public EmailSender(String senderEmail, String senderPassword, String recipientEmail,
                       String subject, String messageBody, EmailCallback callback) {
        this.senderEmail = senderEmail;
        this.senderPassword = senderPassword;
        this.recipientEmail = recipientEmail;
        this.subject = subject;
        this.messageBody = messageBody;
        this.callback = callback;
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(senderEmail, senderPassword);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(senderEmail));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
            message.setSubject(subject);
            message.setText(messageBody);
            Transport.send(message);
            return true;
        } catch (MessagingException e) {
            errorMessage = e.getMessage();
            return false;
        }
    }

    @Override
    protected void onPostExecute(Boolean success) {
        if (callback != null) {
            if (success) {
                callback.onSuccess();
            } else {
                callback.onFailure(errorMessage);
            }
        }
    }
}
