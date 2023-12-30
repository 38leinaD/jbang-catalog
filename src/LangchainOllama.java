///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS dev.langchain4j:langchain4j:0.25.0
//DEPS dev.langchain4j:langchain4j-ollama:0.25.0
//DEPS org.slf4j:slf4j-jdk14:2.0.10

import static java.lang.System.out;

import java.util.concurrent.CountDownLatch;

import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.language.StreamingLanguageModel;
import dev.langchain4j.model.ollama.OllamaStreamingLanguageModel;
import dev.langchain4j.model.output.Response;

public class LangchainOllama {

    public static void main(String[] args) throws InterruptedException {
        StreamingLanguageModel   model = OllamaStreamingLanguageModel.builder()
            .baseUrl("http://localhost:11434")
            .modelName("mistral")
            .temperature(0.0)
            .build();

        String review = "What is the captial of Germany?";
        out.print("Answer: ");

        CountDownLatch latch = new CountDownLatch(1);
        
        model.generate(review, new StreamingResponseHandler<String>() {

            @Override
            public void onNext(String token) {
                System.out.print(token);
            }

            @Override
            public void onComplete(Response<String> response) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                latch.countDown();
            }
        });

        latch.await();

        System.exit(0);
    }
}