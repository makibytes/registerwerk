package de.makibytes.registerwerk.notification.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("kyc-rejected e-mail template shows the category text only (5C-09)")
class KycRejectedTemplateTest {

    private String render(String code, String leakedReason) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        Context ctx = new Context();
        ctx.setVariable("entityName", "Acme GmbH");
        ctx.setVariable("reasonCode", code);
        ctx.setVariable("reason", leakedReason); // even if a caller still passed it, the template must not print it
        return engine.process("kyc-rejected", ctx);
    }

    @Test
    void rendersCategoryTextNotFreeText() {
        String html = render("DOCUMENTS_UNREADABLE", "sanctions match");
        assertThat(html).contains("could not be read").doesNotContain("sanctions match");
        assertThat(render("CONTACT_SUPPORT", "x")).contains("contact support");
        assertThat(render("SOMETHING_ELSE", "x")).contains("contact support");
    }
}
