///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.quarkus.platform:quarkus-bom:3.6.4@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkus:quarkus-arc
//DEPS io.quarkiverse.langchain4j:quarkus-langchain4j-ollama:0.5.1

//JAVAC_OPTIONS -parameters
//JAVA_OPTIONS -Djava.util.logging.manager=org.jboss.logmanager.LogManager

//Q:CONFIG quarkus.banner.enabled=false
//Q:CONFIG quarkus.log.level=WARN
//Q:CONFIG quarkus.log.category."dev.langchain4j".level=DEBUG
//Q:CONFIG quarkus.langchain4j.ollama.chat-model.model-id=mistral

import static java.lang.System.out;

import com.fasterxml.jackson.annotation.JsonCreator;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import picocli.CommandLine;

@CommandLine.Command
public class QuarkusLangchainOllama implements Runnable {

    @Inject
    TriageService triage;

    @Override
    @ActivateRequestContext
    public void run() {
        String review = "I really love this bank. Not!";
        out.println("Review: " + review);
        out.println("...");
        TriagedReview result = triage.triage(review);

        out.println("Sentiment: " + result.evaluation());
        out.println("Message: " + result.message());
    }
}


@RegisterAiService
interface TriageService {
    @SystemMessage("""
        You are working for a bank, processing reviews about
        financial products. Triage reviews into positive and
        negative ones, responding with a JSON document.
        """
    )
    @UserMessage("""
        Your task is to process the review delimited by ---.
        Apply sentiment analysis to the review to determine
        if it is positive or negative, considering various languages.

        For example:
        - `I love your bank, you are the best!` is a 'POSITIVE' review
        - `J'adore votre banque` is a 'POSITIVE' review
        - `I hate your bank, you are the worst!` is a 'NEGATIVE' review

        Respond with a JSON document containing:
        - the 'evaluation' key set to 'POSITIVE' if the review is
        positive, 'NEGATIVE' otherwise
        - the 'message' key set to a message thanking or apologizing
        to the customer. These messages must be polite and match the
        review's language.

        ---
        {review}
        ---
    """)
    TriagedReview triage(String review);
}

record TriagedReview(Evaluation evaluation, String message) {
    @JsonCreator
    public TriagedReview {}
}

enum Evaluation {
    POSITIVE,
    NEGATIVE
}