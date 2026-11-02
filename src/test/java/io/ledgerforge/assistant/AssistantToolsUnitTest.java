package io.ledgerforge.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantToolsUnitTest {

    @Test
    void amountsAreExactDecimals() {
        assertThat(AssistantTools.major(2550)).isEqualTo("25.50");
        assertThat(AssistantTools.major(-2550)).isEqualTo("-25.50");
        assertThat(AssistantTools.major(5)).isEqualTo("0.05");
        assertThat(AssistantTools.major(0)).isEqualTo("0.00");
    }

    @Test
    void fraudEvidenceParsesReasonsContainingSeparators() {
        var f = AssistantTools.parseEvidence(
                "from=a;to=b;amount=950000;currency=ZAR;score=0.99;model=m1;reasons=Large; late; new recipient");
        assertThat(f).containsEntry("from", "a").containsEntry("amount", "950000").containsEntry("model", "m1")
                .containsEntry("reasons", "Large; late; new recipient");
    }

    @Test
    void answersAreReducedToPlainTextWithoutLinks() {
        assertThat(AssistantService.plainText("See [the report](http://x.example/a) now"))
                .isEqualTo("See the report now");
        assertThat(AssistantService.plainText("![img](https://x.example/p?d=1)")).isEqualTo("img");
        assertThat(AssistantService.plainText("go to www.x.example or ftp://y.example/z"))
                .isEqualTo("go to [link removed] or [link removed]");
        assertThat(AssistantService.plainText("Balance is 25.50 ZAR")).isEqualTo("Balance is 25.50 ZAR");
    }
}
