package co.com.finova.wallet.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import co.com.finova.wallet.usecase.creditwallet.CreditWalletUseCase;
import co.com.finova.wallet.usecase.debitwallet.DebitWalletUseCase;
import co.com.finova.wallet.usecase.reversedebit.ReverseDebitUseCase;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The state lines of the saga command use cases also become events of the current span, with the same text Loki
 * shows, so Jaeger shows what happened inside the span. Observability only:
 * <ul>
 *   <li>only the loggers below, and only INFO and WARN: their lines carry results, reasons, attempts, versions and
 *       ids, never amounts or user data (ERROR lines are left out: a DEBIT mismatch names amounts);</li>
 *   <li>nothing happens without a current span or without tracing (most tests);</li>
 *   <li>the console output and Loki do not change (additivity stays on), and a failure here is swallowed.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SpanEventsLogging {

    static final List<String> LOGGERS = List.of(
            DebitWalletUseCase.class.getName(),
            CreditWalletUseCase.class.getName(),
            ReverseDebitUseCase.class.getName());

    @Bean(destroyMethod = "detach")
    SpanEventsAppender spanEventsAppender(ObjectProvider<Tracer> tracer) {
        SpanEventsAppender appender = new SpanEventsAppender(tracer.getIfAvailable());
        appender.attach();
        return appender;
    }

    /** Adds each INFO or WARN line of its loggers as an event of the current span. */
    static final class SpanEventsAppender extends AppenderBase<ILoggingEvent> {

        private final Tracer tracer;

        SpanEventsAppender(Tracer tracer) {
            this.tracer = tracer;
            setName("SPAN_EVENTS");
        }

        void attach() {
            if (tracer == null || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
                return;
            }
            setContext(context);
            start();
            LOGGERS.forEach(name -> context.getLogger(name).addAppender(this));
        }

        void detach() {
            if (getContext() instanceof LoggerContext context) {
                LOGGERS.forEach(name -> context.getLogger(name).detachAppender(this));
            }
            stop();
        }

        @Override
        protected void append(ILoggingEvent event) {
            try {
                Level level = event.getLevel();
                if (level != Level.INFO && level != Level.WARN) {
                    return;
                }
                Span span = tracer.currentSpan();
                if (span != null) {
                    span.event(event.getFormattedMessage());
                }
            } catch (RuntimeException ignored) {
                // Observability only: a failure here never reaches the log line or the business.
            }
        }
    }
}
