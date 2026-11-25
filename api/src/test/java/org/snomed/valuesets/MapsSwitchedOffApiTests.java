package org.snomed.valuesets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/** A deployment that has switched maps off. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestKeycloakConfig.class)
@TestPropertySource(properties = { "KEYCLOAK_CLIENT_ID=test-client", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"KEYCLOAK_ISSUER_URI=http://keycloak.test", "features.maps.enabled=false" })
class MapsSwitchedOffApiTests {

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	SnowstormClient snowstorm;

	@Test
	void tellsTheFrontendMapsAreOff() throws Exception {
		mvc.perform(get("/api/site"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mapsEnabled").value(false));
	}

	@Test
	void thereIsNoMapsApiEvenForSomeoneSignedIn() throws Exception {
		mvc.perform(get("/api/maps").with(oidcLogin()))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/maps/1").with(oidcLogin()))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/maps").with(oidcLogin()).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void valueSetsStillWork() throws Exception {
		mvc.perform(get("/api/value-sets"))
				.andExpect(status().isOk());
	}
}
