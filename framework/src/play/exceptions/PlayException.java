package play.exceptions;

import play.Invoker;
import play.Play;

import play.Logger;
import org.apache.commons.mail.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Properties;

import java.util.concurrent.atomic.AtomicLong;

import play.Logger;
import org.apache.commons.mail.*;
import play.mvc.Http;
import play.mvc.Scope;
import play.server.Throttle;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Properties;


/**
 * The super class for all Play! exceptions
 */
public abstract class PlayException extends RuntimeException {

    private static final AtomicLong atomicLong = new AtomicLong(System.currentTimeMillis());
    private String id;

    private static final Throttle throttle = new Throttle(5, Throttle.Timespan.Minute);

    void reportError(String stackTrace) {
        if(getCause() != null && getCause().getLocalizedMessage() != null &&
                getCause().getLocalizedMessage().contains("Cannot find enhanced byte class for app_rythm___l_main_html__R_T_C__")){
            Logger.warn("Exception enhanced byte class for app_rythm___l_main_html__R_T_C__");
            return;
        }
        if (Invoker.Suspend.class.isAssignableFrom(this.getClass())) return;
        if (play.exceptions.ActionNotFoundException.class.isAssignableFrom(this.getClass())) return;
        String throttleLast = "";
        switch (throttle.get() ) {
            case OverLimit:
                Logger.warn("Error over limit");
                return;
            case Last: throttleLast = ". Next errors will be suppressed"; //No Break
            case Ok:
                StringBuilder requestInfo = new StringBuilder();
                try {
                    Http.Request request = Http.Request.current();
                    if (request == null) requestInfo.append("No request.");
                    else {
                        requestInfo.append(String.format("url: %s ? %s \n", request.path, request.querystring, request.params.all()));
                        var s = Scope.Session.current();
                        if (s != null && s.all() != null) {
                            requestInfo.append("Session:\n");
                            for (String k : s.all().keySet()) {
                                String v = s.all().get(k);
                                if (v != null) requestInfo.append(k + ": " + v + "\n");
                            }
                        }
                        if (request.headers != null) {
                            requestInfo.append("\n\n\nHeaders:\n");
                            for (String k : request.headers.keySet()) {
                                requestInfo.append(k + ": ");
                                if (request.headers.get(k) != null) {
                                    requestInfo.append(request.headers.get(k).name + " ");
                                    for (String v : request.headers.get(k).values) {
                                        requestInfo.append(v + "\n");
                                    }
                                    requestInfo.append("\n");
                                }
                            }
                        }
                        Scope.Params params = Scope.Params.current();
                        if (params != null & !params.all().isEmpty()) {
                            var pp = params.all();
                            requestInfo.append("\n\n\nParams:\n");
                            for (String k : pp.keySet()) {
                                requestInfo.append(k + ": ");
                                if (pp.get(k) != null) {
                                    for (String v : pp.get(k)) {
                                        requestInfo.append(v + "\n");
                                    }
                                    requestInfo.append("\n");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    requestInfo.append(e.toString());
                }

                Properties p = Play.configuration;
                String errorMonitorigType = p.getProperty("errormonitoring.type", "none");
                if (errorMonitorigType.equalsIgnoreCase("email")) {
                    if (reportErrorIgnore(stackTrace)) return;
                    Logger.error("Sending error monitoring email " + getId());
                    try {
                        SimpleEmail emailer = new SimpleEmail();
                        emailer.setHostName(p.getProperty("errormonitoring.smtphost", "smtp.gmail.com"));
                        emailer.setAuthentication(p.getProperty("errormonitoring.user", ""), p.getProperty("errormonitoring.password", ""));
                        emailer.setSSL((p.getProperty("errormonitoring.emailssl", "true").equalsIgnoreCase("true")));
                        emailer.setSslSmtpPort(p.getProperty("errormonitoring.port", "587"));
                        emailer.setSmtpPort(Integer.parseInt(p.getProperty("errormonitoring.port", "587")));
                        emailer.setMsg("Error " + getId() + "\n" + getErrorTitle() + "\n" + getErrorDescription() + "\n\n" + stackTrace + "\n\n" + requestInfo);
                        emailer.setFrom(p.getProperty("errormonitoring.emailfrom", ""));
                        emailer.addTo(p.getProperty("errormonitoring.emailto", ""));
                        emailer.setSubject(p.getProperty("application.name", "Play!") + " Error " + getId() + throttleLast);
                        emailer.send();
                    } catch (EmailException e) {
                        Logger.error(e, "Failed to send error monitoring email " + getId());
                    }
                }
                return;
        }

    }

    boolean reportErrorIgnore(String stackTrace){
        if(stackTrace==null) stackTrace="";
        if(getErrorTitle().equals("Oops: IllegalStateException") && stackTrace.startsWith("java.lang.IllegalStateException: Error when handling upload")) return true;

        return false;
    }

    public PlayException() {
        setId();
        if (getCause() != null){
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            PrintStream s = new PrintStream(o);
            getCause().printStackTrace(s);
            reportError(o.toString());
        }
        else {
            reportError("No Stack Trace");
        }

    }

    public PlayException(String message) {
        super(message);
        setId();
    }

    public PlayException(String message, Throwable cause) {
        super(message, cause);
        setId();
        if (cause != null){
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            PrintStream s = new PrintStream(o);
            cause.printStackTrace(s);
            reportError(o.toString());
        }
        else {
            reportError("No Stack Trace");
        }
    }

    void setId() {
        long nid = atomicLong.incrementAndGet();
        id = Long.toString(nid, 26);
    }

    public abstract String getErrorTitle();

    public abstract String getErrorDescription();

    public boolean isSourceAvailable() {
        return this instanceof SourceAttachment;
    }

    public Integer getLineNumber() {
        return -1;
    }

    public String getSourceFile() {
        return "";
    }

    public String getId() {
        return id;
    }

    @Deprecated
    public static StackTraceElement getInterestingStrackTraceElement(Throwable cause) {
        return getInterestingStackTraceElement(cause);
    }

    public static StackTraceElement getInterestingStackTraceElement(Throwable cause) {
        for (StackTraceElement stackTraceElement : cause.getStackTrace()) {
            if (stackTraceElement.getLineNumber() > 0 && Play.classes.hasClass(stackTraceElement.getClassName())) {
                return stackTraceElement;
            }
        }
        return null;
    }

    public String getMoreHTML() {
        return null;
    }
}
