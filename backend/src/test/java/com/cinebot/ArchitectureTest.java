package com.cinebot;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Module boundaries of the modular monolith, as executable rules. Each rule records a design decision;
 * breaking one should be a conscious change to the architecture, not an accident.
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.cinebot");

    @Test
    void agentCannotTouchPayments() {
        // The agent may show a Confirm & Pay card; only a human click on the payment API moves money.
        noClasses().that().resideInAPackage("com.cinebot.agent..")
                .should().dependOnClassesThat().resideInAPackage("..payment..")
                .because("payment authorisation must come from the user, never from the model")
                .check(CLASSES);
    }

    @Test
    void bookingDoesNotDependOnPayment() {
        // booking -> payment is inverted through the RefundRequester port
        noClasses().that().resideInAPackage("com.cinebot.booking..")
                .should().dependOnClassesThat().resideInAPackage("com.cinebot.payment..")
                .check(CLASSES);
    }

    @Test
    void catalogDoesNotDependOnShowsOrBookings() {
        // "now showing in city" comes through the NowShowingQuery port implemented by the show module
        noClasses().that().resideInAnyPackage("com.cinebot.movie..", "com.cinebot.genre..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.cinebot.show..", "com.cinebot.theatre..", "com.cinebot.booking..")
                .check(CLASSES);
    }

    @Test
    void inventoryAndVenuesDoNotDependOnHigherLevelModules() {
        noClasses().that().resideInAnyPackage("com.cinebot.seat..", "com.cinebot.screen..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.cinebot.show..", "com.cinebot.booking..", "com.cinebot.payment..",
                        "com.cinebot.theatre..")
                .check(CLASSES);
    }

    @Test
    void outboxIsGenericInfrastructure() {
        noClasses().that().resideInAPackage("com.cinebot.outbox..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.cinebot.booking..", "com.cinebot.payment..", "com.cinebot.notification..",
                        "com.cinebot.analytics..")
                .check(CLASSES);
    }

    @Test
    void paymentProviderDetailsStayBehindTheGatewayInterface() {
        noClasses().that().resideInAPackage("com.cinebot.payment")
                .and().doNotHaveSimpleName("PaymentTestSupport")
                .should().dependOnClassesThat().resideInAPackage("com.cinebot.payment.simulator..")
                .check(CLASSES);
    }
}
