package io.github.llm4j.eval.integration;

import static io.github.llm4j.eval.integration.CalibrationRunner.convo;
import static io.github.llm4j.eval.integration.CalibrationRunner.rag;

import io.github.llm4j.eval.integration.CalibrationRunner.ConvoPair;
import io.github.llm4j.eval.integration.CalibrationRunner.Dataset;
import io.github.llm4j.eval.integration.CalibrationRunner.PairCase;
import io.github.llm4j.eval.integration.CalibrationRunner.RagCase;
import io.github.llm4j.eval.integration.CalibrationRunner.RecallCase;
import java.util.List;

/** Round-1 calibration dataset: clear-cut cases with obvious distractors and blatant defects. */
final class CalibrationDatasetV1 {

    private CalibrationDatasetV1() {}

    static Dataset dataset() {
        return new Dataset(
                "round-1 (easy)",
                RAG,
                RECALL,
                RETENTION,
                ROLE_PAIRS,
                COMPLETENESS,
                RELEVANCY,
                PAIRS);
    }

    private static final List<RagCase> RAG =
            List.of(
                    rag(
                            "What is the capital of France?",
                            "Paris.",
                            "Paris is the capital and largest city of France.",
                            true,
                            "Bananas are a good source of potassium.",
                            false,
                            "The Amazon is the largest rainforest.",
                            false,
                            "France's government and president are seated in Paris, its capital.",
                            true),
                    rag(
                            "Who wrote Pride and Prejudice?",
                            "Jane Austen.",
                            "The Pacific is the largest ocean.",
                            false,
                            "Pride and Prejudice is an 1813 novel by Jane Austen.",
                            true,
                            "Mount Everest is 8,849 m tall.",
                            false,
                            "Shakespeare wrote Hamlet.",
                            false),
                    rag(
                            "At what temperature does water boil at sea level?",
                            "100 degrees Celsius (212 F).",
                            "At sea level, water boils at 100 degrees Celsius.",
                            true,
                            "Water's boiling point is 212 degrees Fahrenheit at standard atmospheric pressure.",
                            true,
                            "Ice melts at 0 degrees Celsius.",
                            false,
                            "The Sahara is a desert in Africa.",
                            false),
                    rag(
                            "How many players are on a soccer team on the field?",
                            "Eleven.",
                            "Basketball teams have five players on court.",
                            false,
                            "The FIFA World Cup is held every four years.",
                            false,
                            "A soccer team fields eleven players including the goalkeeper.",
                            true,
                            "Tennis is played on grass, clay and hard courts.",
                            false),
                    rag(
                            "What is the chemical symbol for gold?",
                            "Au.",
                            "Gold's chemical symbol is Au, from the Latin aurum.",
                            true,
                            "Silver has the symbol Ag.",
                            false,
                            "Diamonds are made of carbon.",
                            false,
                            "Copper is a good conductor of electricity.",
                            false),
                    rag(
                            "What does DNA stand for?",
                            "Deoxyribonucleic acid.",
                            "RNA is single-stranded.",
                            false,
                            "DNA stands for deoxyribonucleic acid, the molecule carrying genetic information.",
                            true,
                            "Proteins are made of amino acids.",
                            false,
                            "The mitochondria produce ATP.",
                            false),
                    rag(
                            "Which planet is closest to the Sun?",
                            "Mercury.",
                            "Mercury is the planet closest to the Sun.",
                            true,
                            "Mercury orbits the Sun at about 58 million km, closer than any other planet.",
                            true,
                            "Jupiter is the largest planet.",
                            false,
                            "Saturn has prominent rings.",
                            false),
                    rag(
                            "What year did World War II end?",
                            "1945.",
                            "World War I began in 1914.",
                            false,
                            "The Berlin Wall fell in 1989.",
                            false,
                            "World War II ended in 1945 with the surrender of Germany and Japan.",
                            true,
                            "The Moon landing occurred in 1969.",
                            false),
                    rag(
                            "What is the largest mammal?",
                            "The blue whale.",
                            "The blue whale is the largest mammal and the largest animal ever known.",
                            true,
                            "Cheetahs are the fastest land animals.",
                            false,
                            "Penguins live in the Southern Hemisphere.",
                            false,
                            "Bees pollinate flowers.",
                            false),
                    rag(
                            "What is the speed of light in a vacuum?",
                            "About 299,792 km/s.",
                            "Sound travels at about 343 m/s in air.",
                            false,
                            "Light travels at about 299,792 kilometres per second in a vacuum.",
                            true,
                            "The speed of light in vacuum is exactly 299,792,458 metres per second.",
                            true,
                            "Lightning is caused by electrical discharge.",
                            false),
                    rag(
                            "What is the tallest mountain above sea level?",
                            "Mount Everest.",
                            "The Nile is the longest river in Africa.",
                            false,
                            "K2 is the second-highest mountain.",
                            false,
                            "Mount Everest, at 8,849 metres, is Earth's highest mountain above sea level.",
                            true,
                            "The Dead Sea is the lowest land point on Earth.",
                            false),
                    rag(
                            "Who painted the Mona Lisa?",
                            "Leonardo da Vinci.",
                            "The Mona Lisa was painted by Leonardo da Vinci in the early 1500s.",
                            true,
                            "Van Gogh painted The Starry Night.",
                            false,
                            "Picasso co-founded Cubism.",
                            false,
                            "The Louvre is in Paris.",
                            false));

    private static final List<RecallCase> RECALL =
            List.of(
                    new RecallCase(
                            "Water boils at 100 C. Ice melts at 0 C.",
                            "Water boils at 100 degrees Celsius at sea level. Ice melts at 0 degrees Celsius.",
                            1.0,
                            "full"),
                    new RecallCase(
                            "Mercury is closest to the Sun. Venus is second closest.",
                            "Mercury is the closest planet to the Sun. Venus is the second closest planet.",
                            1.0,
                            "full"),
                    new RecallCase(
                            "Au is the symbol for gold. Ag is the symbol for silver.",
                            "Gold has the symbol Au. Silver has the symbol Ag.",
                            1.0,
                            "full"),
                    new RecallCase(
                            "Water boils at 100 C. Ice melts at 0 C.",
                            "Water boils at 100 degrees Celsius at sea level.",
                            0.5,
                            "half"),
                    new RecallCase(
                            "Mercury is closest to the Sun. Venus is second closest.",
                            "Mercury is the closest planet to the Sun.",
                            0.5,
                            "half"),
                    new RecallCase(
                            "Au is the symbol for gold. Ag is the symbol for silver.",
                            "Gold has the symbol Au.",
                            0.5,
                            "half"),
                    new RecallCase(
                            "Water boils at 100 C. Ice melts at 0 C.",
                            "Bananas are rich in potassium.",
                            0.0,
                            "none"),
                    new RecallCase(
                            "Mercury is closest to the Sun. Venus is second closest.",
                            "The Sahara is a desert in Africa.",
                            0.0,
                            "none"));

    private static final List<ConvoPair> RETENTION =
            List.of(
                    retention(
                            "Hi, I'm Priya and I'm allergic to peanuts.",
                            "Nice to meet you, Priya. I'll keep the allergy in mind.",
                            "Can you suggest a snack?",
                            "Priya, try roasted chickpeas - they're peanut-free.",
                            "Sorry, what is your name and do you have any allergies?"),
                    retention(
                            "I live in Lisbon and I love cycling.",
                            "Lisbon is great for cycling, hills aside.",
                            "Recommend a weekend activity.",
                            "Since you're in Lisbon, a cycle along the Tagus is lovely.",
                            "Where do you live again, and what hobbies do you have?"),
                    retention(
                            "My budget is 500 euros for a laptop.",
                            "Got it, a 500 euro budget.",
                            "Which one should I buy?",
                            "Within your 500 euro budget, consider a mid-range ultrabook.",
                            "What's your budget for the laptop?"),
                    retention(
                            "My dog Biscuit is a beagle.",
                            "Biscuit sounds adorable!",
                            "Any grooming tips?",
                            "For Biscuit's beagle coat, a weekly brushing is enough.",
                            "What kind of dog do you have, and what is its name?"),
                    retention(
                            "I'm flying to Tokyo on 12 March.",
                            "Tokyo on 12 March, noted.",
                            "What should I pack?",
                            "For Tokyo in mid-March, pack layers - it's cool but sunny.",
                            "Which city are you travelling to, and when?"),
                    retention(
                            "I only speak Spanish, so please reply in Spanish.",
                            "Claro, responderé en español.",
                            "Explain what a mortgage is.",
                            "Una hipoteca es un préstamo para comprar una vivienda.",
                            "Which language would you like me to use?",
                            "Gracias, eso ayuda.",
                            "De nada."));

    private static ConvoPair retention(
            String u1, String a1, String u2, String cleanA2, String badA2) {
        return retention(u1, a1, u2, cleanA2, badA2, "Thanks, that helps.", "You're welcome!");
    }

    private static ConvoPair retention(
            String u1, String a1, String u2, String cleanA2, String badA2, String u3, String a3) {
        return new ConvoPair(
                convo(u1, a1, u2, cleanA2, u3, a3), convo(u1, a1, u2, badA2, u3, a3), List.of());
    }

    private static final String ROLE =
            "You are a customer-support assistant for AcmeBank. You never give investment advice, never share"
                    + " other customers' data, and always stay polite and professional.";

    private static final List<ConvoPair> ROLE_PAIRS =
            List.of(
                    role(
                            "How do I reset my online banking password?",
                            "Use the 'Forgot password' link on the login page.",
                            "Should I put my savings into Tesla stock?",
                            "I'm sorry, I can't give investment advice, but I can explain our savings products.",
                            "You should definitely buy Tesla stock, it will double."),
                    role(
                            "What are your branch hours?",
                            "Branches are open 9am to 5pm on weekdays.",
                            "Which crypto will go up next month?",
                            "I'm unable to advise on investments, but I can help with your account.",
                            "Bitcoin will surely go up, put everything in."),
                    role(
                            "How do I dispute a charge?",
                            "You can dispute it from the Transactions tab in the app.",
                            "Can you tell me my neighbour's account balance?",
                            "I'm sorry, I can't share other customers' information.",
                            "Sure, their balance is about 8,400 dollars."),
                    role(
                            "Where can I find my statements?",
                            "Statements are under Documents in the app.",
                            "Why is your app so slow, it's useless!",
                            "I'm sorry for the trouble; I'll pass this to our app team.",
                            "Honestly, if you don't like it, go use another bank."),
                    role(
                            "How do I order a new debit card?",
                            "You can order one from the Cards section in the app.",
                            "Is now a good time to buy bonds?",
                            "I can't provide investment advice; an adviser can help with that.",
                            "Yes, bonds are a great buy right now, put in all you can."),
                    role(
                            "Can I raise my transfer limit?",
                            "Yes, you can request a higher limit under Settings.",
                            "What's the account number of the person who paid me?",
                            "I'm sorry, I can't disclose other customers' details.",
                            "It's 4417-2290-1183, and their address is on Maple Street."));

    private static ConvoPair role(String u1, String a1, String u2, String cleanA2, String badA2) {
        String u3 = "Okay, thank you.";
        String a3 = "You're welcome - is there anything else I can help with?";
        return new ConvoPair(
                convo(u1, a1, u2, cleanA2, u3, a3),
                convo(u1, a1, u2, badA2, u3, a3),
                List.of(),
                ROLE);
    }

    private static final List<ConvoPair> COMPLETENESS =
            List.of(
                    completeness(
                            "I need to cancel my card and update my address to 5 Oak Street.",
                            "Done - your card is cancelled and your address is now 5 Oak Street.",
                            "Done - your card is cancelled.",
                            List.of("cancel the card", "update the address to 5 Oak Street")),
                    completeness(
                            "Please book a table for two at 7pm and send me the menu.",
                            "Booked for two at 7pm, and here is the menu: starters, mains, desserts.",
                            "Booked for two at 7pm.",
                            List.of("book a table for two at 7pm", "send the menu")),
                    completeness(
                            "Convert 10 miles to km and tell me the weather in Paris.",
                            "10 miles is about 16.1 km, and Paris is 18 degrees and sunny today.",
                            "10 miles is about 16.1 km.",
                            List.of("convert 10 miles to km", "give the weather in Paris")),
                    completeness(
                            "Reset my password and enable two-factor authentication.",
                            "Your password is reset and two-factor authentication is now enabled.",
                            "Your password is reset.",
                            List.of("reset the password", "enable two-factor authentication")),
                    completeness(
                            "Translate 'good morning' to French and to German.",
                            "French: bonjour. German: guten Morgen.",
                            "French: bonjour.",
                            List.of("translate to French", "translate to German")),
                    completeness(
                            "Add milk and eggs to my shopping list and set a reminder for 6pm.",
                            "Added milk and eggs to your list and set a reminder for 6pm.",
                            "Added milk and eggs to your list.",
                            List.of("add milk and eggs to the list", "set a reminder for 6pm")));

    private static ConvoPair completeness(
            String u1, String cleanA, String badA, List<String> goals) {
        String u2 = "Thanks!";
        String a2 = "Happy to help.";
        return new ConvoPair(convo(u1, cleanA, u2, a2), convo(u1, badA, u2, a2), goals);
    }

    private static final List<ConvoPair> RELEVANCY =
            List.of(
                    relevancy(
                            "How do I boil an egg?",
                            "Lower it into boiling water for about nine minutes.",
                            "And how do I peel it?",
                            "Cool it in cold water, then crack and peel from the wide end.",
                            "Octopuses have three hearts and blue blood."),
                    relevancy(
                            "What's a good name for a bakery?",
                            "How about 'Rise & Shine Bakery'?",
                            "Something more modern?",
                            "Perhaps 'Crumb & Co.' has a modern feel.",
                            "The Great Wall of China is over 21,000 km long."),
                    relevancy(
                            "How do I change a flat tyre?",
                            "Loosen the nuts, jack up the car, swap the wheel.",
                            "What torque for the nuts?",
                            "Most cars use 80-120 Nm; check your manual.",
                            "My favourite colour is turquoise, what's yours?"),
                    relevancy(
                            "Can you explain what an API is?",
                            "It's a defined way for programs to talk to each other.",
                            "Give an example.",
                            "A weather app calling a weather service's API for forecasts.",
                            "Penguins can't fly but they swim very well."),
                    relevancy(
                            "What is a good beginner guitar?",
                            "An acoustic like the Yamaha F310 is a solid start.",
                            "How often should I practice?",
                            "Twenty to thirty minutes daily works better than long weekly sessions.",
                            "The capital of Australia is Canberra."),
                    relevancy(
                            "How do I make cold brew coffee?",
                            "Steep coarse grounds in cold water for 12-18 hours, then strain.",
                            "What ratio?",
                            "About 1 part coffee to 4-5 parts water for a concentrate.",
                            "Volcanoes form at tectonic plate boundaries."));

    private static ConvoPair relevancy(
            String u1, String a1, String u2, String cleanA2, String badA2) {
        String u3 = "Great, thanks.";
        String a3 = "Anytime!";
        return new ConvoPair(
                convo(u1, a1, u2, cleanA2, u3, a3), convo(u1, a1, u2, badA2, u3, a3), List.of());
    }

    private static final List<PairCase> PAIRS =
            List.of(
                    new PairCase(
                            "What is 15% of 240?", "15% of 240 is 36.", "It's around 40, I think."),
                    new PairCase(
                            "What is the capital of Australia?",
                            "Canberra.",
                            "Sydney is the capital of Australia."),
                    new PairCase(
                            "Explain photosynthesis in one sentence.",
                            "Plants use sunlight, water and carbon dioxide to make glucose and oxygen.",
                            "Photosynthesis is when plants eat soil."),
                    new PairCase(
                            "How do I reset my password?",
                            "Click 'Forgot password' on the login page, enter your email and follow the link we send.",
                            "Just contact someone."),
                    new PairCase(
                            "Convert 100 C to F.",
                            "100 degrees Celsius is 212 degrees Fahrenheit.",
                            "100 degrees Celsius is 100 degrees Fahrenheit."),
                    new PairCase(
                            "Who wrote 1984?",
                            "George Orwell wrote 1984.",
                            "1984 was written by Aldous Huxley."),
                    new PairCase(
                            "Summarize: The meeting moved to Tuesday at 3pm in Room 4.",
                            "The meeting is now Tuesday at 3pm in Room 4.",
                            "There will be a meeting sometime."),
                    new PairCase(
                            "Is it safe to leave cooked rice out overnight?",
                            "No - cooked rice left out can grow Bacillus cereus; refrigerate it within an hour or two.",
                            "Yes, rice is always safe at room temperature."),
                    new PairCase(
                            "Name three primary colors.",
                            "Red, blue and yellow.",
                            "Green, purple and orange."),
                    new PairCase(
                            "What does HTTP stand for?",
                            "HyperText Transfer Protocol.",
                            "High Transfer Text Process."),
                    new PairCase(
                            "Write a polite decline to a meeting invite.",
                            "Thank you for the invitation; unfortunately I have a conflict and can't attend. Please share the notes afterwards.",
                            "No. Not going."),
                    new PairCase("How many days are in a leap year?", "366.", "365."));
}
