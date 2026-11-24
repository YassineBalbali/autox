package com.autox.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The help pages, searched by the user's words: which section answers, and when none does. */
class HelpIndexTest {

    private final HelpIndex index = new HelpIndex(new ObjectMapper());

    @ParameterizedTest(name = "« {0} » → {1}")
    @CsvSource(delimiter = '|', value = {
            "Comment supprimer mon compte ?                     | aide#comment-supprimer-mon-compte-ou-obtenir",
            "J'ai oublié mon mot de passe                        | aide#j-ai-oublie-mon-mot-de",
            "Les annonces sont-elles réelles ?                   | aide#les-annonces-sont-elles-reelles",
            "je n'ai pas reçu l'e-mail de vérification           | aide#je-n-ai-pas-recu-l",
            "comment prendre les photos de ma voiture            | conseils-vente#les-photos",
            "où est le bouton pour signaler une annonce          | signalement#ou-est-le-bouton",
            "comment arrêter les alertes par e-mail              | aide#comment-arreter-les-alertes",
            "que faut-il vérifier sur la carte grise à la visite | conseils-achat#pendant-la-visite",
            "ouvrir un compte professionnel avec un SIRET        | pro#ouvrir-un-compte",
            "est-ce que c'est payant ?                           | pro#ce-qui-n-existe-pas",
            "Comment contacter le vendeur ?                      | aide#comment-contacter-un-vendeur",
            "je veux le téléphone du vendeur                     | aide#comment-contacter-un-vendeur",
            // The planner's queries in the second evaluation, a few words of the question.
            "arrêter les mails d'alerte                          | aide#comment-arreter-les-alertes",
            "annonce disparue                                    | aide#pourquoi-mon-annonce-a-t-elle",
    })
    @DisplayName("finds the section that answers, first")
    void answers(String question, String section) {
        assertThat(keys(index.search(question))).first().isEqualTo(section);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Quelle est la capitale du Pérou ?",
            "Écris-moi un poème sur les voitures",
            "?",
            "siège social AutoX",
            "Où se trouve le siège social d'AutoX ?",
    })
    @DisplayName("answers NOT_IN_HELP when no section holds half the question's words, two at least, or there are none")
    void notInHelp(String question) {
        assertThat(index.search(question))
                .isEqualTo(ToolResult.refusal(ToolResult.Reason.NOT_IN_HELP));
    }

    @Test
    @DisplayName("reads a short form the pages never write as the words it begins: « pro », the professional pages")
    void shortForm() {
        // The planner's query in the third evaluation, for « c koi un compte pro, ça sert a quoi ».
        assertThat(keys(index.search("compte pro"))).first().asString().startsWith("pro#");
        // « siège social » stays NOT_IN_HELP (notInHelp): « siege » begins no word of the pages.
    }

    @Test
    @DisplayName("answers a question that is a page's title with that page")
    void pageTitle() {
        assertThat(keys(index.search("Comment ça marche ?"))).first().asString().startsWith("comment-ca-marche#");
    }

    @Test
    @DisplayName("answers NOT_IN_HELP to nothing at all")
    void empty() {
        assertThat(index.search("")).isEqualTo(ToolResult.refusal(ToolResult.Reason.NOT_IN_HELP));
    }

    @ParameterizedTest(name = "« {0} » → {1}")
    @CsvSource(delimiter = '|', value = {
            "Comment supprimer mon compte ?       | aide#comment-supprimer-mon-compte-ou-obtenir",
            "J'ai oublié mon mot de passe          | aide#j-ai-oublie-mon-mot-de",
            "comment arrêter les alertes par e-mail | aide#comment-arreter-les-alertes",
    })
    @DisplayName("leaves out a passage far below the best one: it answers another question")
    void onlyTheRelevant(String question, String section) {
        assertThat(keys(index.search(question))).containsExactly(section);
    }

    @Test
    @DisplayName("keeps a second passage on the same subject, close to the best one")
    void aSecondOnTheSameSubject() {
        assertThat(keys(index.search("où est le bouton pour signaler une annonce")))
                .containsExactly("signalement#ou-est-le-bouton", "signalement#un-message-suspect");
    }

    @Test
    @DisplayName("returns at most two passages, with the text as written and without its markup")
    void passages() {
        ToolResult.Answer<List<HelpIndex.Passage>> answer =
                (ToolResult.Answer<List<HelpIndex.Passage>>) index.search("annonce supprimer données compte");
        assertThat(answer.value()).hasSizeLessThanOrEqualTo(HelpIndex.MAX_PASSAGES);

        HelpIndex.Passage deletion = answer.value().getFirst();
        assertThat(deletion.path()).isEqualTo("/aide");
        assertThat(deletion.pageTitle()).isEqualTo("Centre d’aide");
        assertThat(deletion.texts().getFirst()).contains("[Mes données](/mon-compte/donnees)", "{contact}");
        assertThat(deletion.plainText())
                .contains("Dans Mes données :", "Une question : l’adresse de contact.")
                .doesNotContain("[", "](", "**", "{contact}");
    }

    @Test
    @DisplayName("indexes the seven help pages and none of the legal ones")
    void pages() {
        assertThat(index.sectionKeys().stream().map(key -> key.substring(0, key.indexOf('#'))).distinct())
                .containsExactlyInAnyOrder("aide", "comment-ca-marche", "conseils-achat", "conseils-vente", "pro",
                        "signalement", "a-propos");
    }

    @Test
    @DisplayName("refuses a query longer than 200 characters")
    void long_() {
        assertThatThrownBy(() -> index.search("a".repeat(HelpIndex.MAX_QUERY + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("folds case and accents, drops common words, keeps a six-letter stem")
    void terms() {
        assertThat(HelpIndex.terms("Comment VÉRIFIER mes Photos ?")).containsExactly("verifi", "photo");
        assertThat(HelpIndex.terms("la vérification")).containsExactly("verifi");
    }

    private static List<String> keys(ToolResult<List<HelpIndex.Passage>> result) {
        assertThat(result).isInstanceOf(ToolResult.Answer.class);
        return ((ToolResult.Answer<List<HelpIndex.Passage>>) result).value().stream()
                .map(p -> p.pageId() + "#" + p.sectionId())
                .toList();
    }
}
