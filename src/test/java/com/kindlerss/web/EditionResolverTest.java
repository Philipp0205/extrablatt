package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which host gets the accessible edition. The interesting case is the deployment
 * that added the subdomain but not the setting, because nothing about it looks
 * broken from the outside: the site answers, it just answers with the edition
 * the reader could not read in the first place.
 */
class EditionResolverTest {

    @Test
    void theConfiguredHostGetsTheAccessibleEdition() {
        EditionResolver resolver = resolverFor("accessibility.extrablatt.app");

        assertEquals(Edition.ACCESSIBLE, resolver.resolve(requestTo("accessibility.extrablatt.app")));
        assertEquals(Edition.ACCESSIBLE, resolver.resolve(requestTo("ACCESSIBILITY.EXTRABLATT.APP")));
        assertEquals(Edition.STANDARD, resolver.resolve(requestTo("reader.extrablatt.app")));
    }

    @Test
    void anAccessibilitySubdomainWorksWithoutBeingConfigured() {
        EditionResolver resolver = resolverFor(null);

        assertEquals(Edition.ACCESSIBLE, resolver.resolve(requestTo("accessibility.extrablatt.app")));
        assertEquals(Edition.ACCESSIBLE, resolver.resolve(requestTo("accessibility.localhost")));
        assertEquals(Edition.STANDARD, resolver.resolve(requestTo("reader.extrablatt.app")));
        assertEquals(Edition.STANDARD, resolver.resolve(requestTo("localhost")));
    }

    @Test
    void aConfiguredDomainDecidesOnItsOwn() {
        // Named something else on purpose: the convention is a fallback, not an
        // extra rule layered on top of what the deployment actually asked for.
        EditionResolver resolver = resolverFor("easy.extrablatt.app");

        assertEquals(Edition.ACCESSIBLE, resolver.resolve(requestTo("easy.extrablatt.app")));
        assertEquals(Edition.STANDARD, resolver.resolve(requestTo("accessibility.extrablatt.app")));
    }

    private static EditionResolver resolverFor(String domain) {
        AppProperties properties = new AppProperties(null, null, null, null, null, null, null, null,
                new AppProperties.Accessibility(domain), null);
        return new EditionResolver(properties, new MockEnvironment());
    }

    private static MockHttpServletRequest requestTo(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setServerName(host);
        return request;
    }
}
