package com.autox.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The {@code search_help} tool: the help pages' sections, searched by their words.
 *
 * <p>The text is {@code assistant/help.fr.json}, a copy of frontend/content/help.fr.json, which the
 * site's help pages render from. The API's image is built from backend/ alone, so it cannot read the
 * frontend's file; {@code HelpContentCopyTest} fails when the two differ. The legal pages are not in
 * it, by decision (docs/assistant-plan.md, D5): the assistant links to them and never quotes them.
 *
 * <p>An in-memory index rather than PostgreSQL full text: a few dozen sections, read once at
 * startup, with nothing to migrate. Each section is scored by BM25 over its page title and its heading
 * (each counted twice: they say what the section is about) and its text. Words are folded (case,
 * accents), common French words are dropped, and a word is cut to its first six letters after a
 * plural {@code s}, so that "photos" finds "photo" and "vérifier" finds "vérification". A section is
 * a candidate only when it holds at least half of the question's words. The best is returned, and
 * one more only if it scores at least {@link #RELATIVE_SCORE} of the best: a passage far below the
 * best one answers another question (« supprimer mon compte » also finds the alerts, which a
 * passage tells how to stop from the account). Nothing qualifying is {@code NOT_IN_HELP}, which the user sees as "not found in our help pages".
 */
@Component
public class HelpIndex {

    public static final String RESOURCE = "assistant/help.fr.json";
    public static final int MAX_PASSAGES = 2;
    /**
     * A second passage's score, at least, as a share of the best one's. Measured on the questions
     * of HelpIndexTest: a second section on the same subject (two ways to report, what is free)
     * scores 0.85 of the best or more; the alerts after « supprimer mon compte » score 0.51, the
     * new-device e-mail after « mot de passe oublié », 0.42.
     */
    public static final double RELATIVE_SCORE = 0.6;
    /** As for the smart search's query: a question, not a document. */
    public static final int MAX_QUERY = 200;

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int STEM = 6;
    private static final Pattern NOT_A_WORD = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    /** Words that say nothing about which section answers. "autox" is on every page. */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "ai", "as", "au", "aux", "avec", "ce", "ces", "cet", "cette", "comment", "d", "dans", "de", "des",
            "du", "elle", "elles", "en", "est", "et", "etre", "faire", "faut", "il", "ils", "j", "je", "l", "la",
            "le", "les", "leur", "leurs", "lui", "m", "ma", "me", "mes", "moi", "mon", "n", "ne", "nos", "notre",
            "nous", "on", "ou", "par", "pas", "peut", "peux", "plus", "pour", "pourquoi", "puis", "qu", "quand",
            "que", "quel", "quelle", "quelles", "quels", "qui", "quoi", "s", "sa", "se", "ses", "si", "son",
            "sont", "sur", "t", "ta", "te", "tes", "toi", "ton", "tu", "un", "une", "vos", "votre", "vous", "y",
            "autox", "site", "dois", "doit", "veux", "voudrais", "bonjour", "merci", "svp");

    private final List<Section> sections;
    private final double averageLength;
    private final Map<String, Integer> documentFrequency = new HashMap<>();

    @Autowired
    public HelpIndex(ObjectMapper mapper) {
        this(read(mapper));
    }

    HelpIndex(JsonNode content) {
        List<Section> all = new ArrayList<>();
        for (JsonNode page : content.path("pages")) {
            for (JsonNode section : page.path("sections")) {
                all.add(Section.of(page, section));
            }
        }
        if (all.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " has no sections");
        }
        this.sections = List.copyOf(all);
        this.averageLength = all.stream().mapToInt(s -> s.terms().size()).average().orElse(1);
        for (Section section : all) {
            for (String term : new LinkedHashSet<>(section.terms())) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
    }

    private static JsonNode read(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    /** @param query the user's words (the plan's validator checks they are), at most {@link #MAX_QUERY} characters */
    public ToolResult<List<Passage>> search(String query) {
        if (query == null || query.length() > MAX_QUERY) {
            throw new IllegalArgumentException("A help query of at most " + MAX_QUERY + " characters");
        }
        Set<String> asked = new LinkedHashSet<>(terms(query));
        if (asked.isEmpty()) {
            return ToolResult.refusal(ToolResult.Reason.NOT_IN_HELP);
        }
        int needed = (asked.size() + 1) / 2;
        List<Map.Entry<Section, Double>> ranked = sections.stream()
                .filter(section -> asked.stream().filter(section.terms()::contains).count() >= needed)
                .map(section -> Map.entry(section, score(section, asked)))
                .sorted(Map.Entry.<Section, Double>comparingByValue(Comparator.reverseOrder()))
                .toList();
        double floor = ranked.isEmpty() ? 0 : ranked.getFirst().getValue() * RELATIVE_SCORE;
        List<Passage> found = ranked.stream()
                .filter(entry -> entry.getValue() >= floor)
                .limit(MAX_PASSAGES)
                .map(entry -> entry.getKey().passage())
                .toList();
        return found.isEmpty() ? ToolResult.refusal(ToolResult.Reason.NOT_IN_HELP) : ToolResult.answer(found);
    }

    /** The sections indexed, for the tests: page id and section id. */
    List<String> sectionKeys() {
        return sections.stream().map(s -> s.passage().pageId() + "#" + s.passage().sectionId()).toList();
    }

    private double score(Section section, Set<String> asked) {
        double score = 0;
        int length = section.terms().size();
        for (String term : asked) {
            long frequency = section.terms().stream().filter(term::equals).count();
            if (frequency == 0) {
                continue;
            }
            int containing = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1 + (sections.size() - containing + 0.5) / (containing + 0.5));
            score += idf * frequency * (K1 + 1) / (frequency + K1 * (1 - B + B * length / averageLength));
        }
        return score;
    }

    /** Folded, split, stopwords dropped, cut to the stem. */
    static List<String> terms(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(NOT_A_WORD.split(folded))
                .filter(word -> !word.isEmpty() && !STOPWORDS.contains(word))
                .map(HelpIndex::stem)
                .toList();
    }

    private static String stem(String word) {
        String singular = word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
        return singular.length() > STEM ? singular.substring(0, STEM) : singular;
    }

    /** A text of the file without its markup: links as their words, bold as plain, the contact address named. */
    static String plain(String marked) {
        return LINK.matcher(marked).replaceAll("$1").replace("**", "").replace("{contact}", "l’adresse de contact");
    }

    /**
     * One section, as the answer shows it.
     *
     * @param texts     the section's paragraphs and list items, with the file's markup, which the
     *                  clients render as the help page does (links included)
     * @param plainText the same without markup, one paragraph or item a line
     * @param path      the page; {@code sectionId} names the section within it
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantHelpPassage")
    public record Passage(String pageId, String sectionId, String pageTitle, String heading, String path,
                          List<String> texts, String plainText) {
    }

    private record Section(Passage passage, List<String> terms) {

        static Section of(JsonNode page, JsonNode section) {
            List<String> texts = new ArrayList<>();
            for (JsonNode block : section.path("blocks")) {
                if (block.hasNonNull("p")) {
                    texts.add(block.get("p").asText());
                }
                for (String list : List.of("ul", "ol")) {
                    block.path(list).forEach(item -> texts.add(item.asText()));
                }
            }
            String heading = section.hasNonNull("heading") ? section.get("heading").asText() : null;
            String title = page.path("title").asText();
            String plainText = String.join("\n", texts.stream().map(HelpIndex::plain).toList());

            List<String> terms = new ArrayList<>(HelpIndex.terms(title));
            terms.addAll(HelpIndex.terms(title));
            if (heading != null) {
                terms.addAll(HelpIndex.terms(heading));
                terms.addAll(HelpIndex.terms(heading));
            }
            terms.addAll(HelpIndex.terms(plainText));
            return new Section(new Passage(page.path("id").asText(), section.path("id").asText(), title, heading,
                    page.path("path").asText(), List.copyOf(texts), plainText), List.copyOf(terms));
        }
    }
}
