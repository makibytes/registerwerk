package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningResult;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningSubjectDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenSanctionsAdapterRequestTest {

    private static final String BODY = """
            {"responses":{"q1":{"results":[{"id":"NK-1","caption":"John Doe","score":0.7,"match":false,
              "last_change":"2026-01-02","topics":["role.pep"]}]}}}""";

    private ScreeningResult run(String threshold, ScreeningSubjectDto subject, MockRestServiceServer[] holder) {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        holder[0] = server;
        server.expect(requestTo("http://os/match/default?algorithm=best"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("birthDate")))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        return new OpenSanctionsAdapter(b, "http://os", "", new BigDecimal(threshold)).screen(subject);
    }

    @Test
    void personRequestCarriesBirthDateAndNationalityAndThresholdIsHonoured() {
        ScreeningSubjectDto s = new ScreeningSubjectDto(UUID.randomUUID(), "NATURAL_PERSON", "John Doe", "DE",
                null, null, LocalDate.of(1980, 5, 17), "FR");
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        ScreeningResult low = run("0.60", s, h);
        h[0].verify();
        assertThat(low.hits()).hasSize(1);
        assertThat(low.hits().get(0).externalId()).isEqualTo("NK-1");
        assertThat(low.thresholdUsed()).isEqualByComparingTo("0.60");
        assertThat(low.dataVersion()).isEqualTo("last_change:2026-01-02");
        assertThat(run("0.85", s, h).hits()).isEmpty();
    }
}
