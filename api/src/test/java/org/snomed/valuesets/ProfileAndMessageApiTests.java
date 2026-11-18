package org.snomed.valuesets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.snomed.valuesets.message.OwnerMessageEmail;
import org.snomed.valuesets.message.ResendClient;
import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestKeycloakConfig.class)
@TestPropertySource(properties = { "KEYCLOAK_CLIENT_ID=test-client", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"KEYCLOAK_ISSUER_URI=http://keycloak.test", "RESEND_API_KEY=test-key",
		"MESSAGES_FROM=Value sets <no-reply@sets.example.org>", "APP_URL=https://sets.example.org/" })
class ProfileAndMessageApiTests {

	private static final String MESSAGE = "{\"message\": \"Could you add occupational asthma?\"}";

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	SnowstormClient snowstorm;

	@MockitoBean
	ResendClient resend;

	@AfterEach
	void removeEverythingMade() {
		jdbc.update("DELETE FROM value_set");
		jdbc.update("DELETE FROM user_profile");
		jdbc.update("DELETE FROM owner_message");
	}

	/** Signed in through Keycloak, with the verified address Keycloak gives when the email scope is granted. */
	private static RequestPostProcessor signedInAs(String subject) {
		return oidcLogin().idToken(token -> token.subject(subject).claim("name", "Person " + subject)
				.claim("email", subject + "@example.org").claim("email_verified", true));
	}

	private static RequestPostProcessor signedInWithoutEmail(String subject) {
		return oidcLogin().idToken(token -> token.subject(subject).claim("name", "Person " + subject));
	}

	private long communitySetOwnedBy(String subject, boolean showOrganisation) {
		jdbc.update("""
				INSERT INTO value_set (name, description, ecl, branch, status, visibility,
				                       owner_subject, owner_name, show_organisation, created_at, updated_at)
				VALUES ('Asthma diagnoses', '', '<< 195967001', 'MAIN', 'PUBLISHED', 'COMMUNITY', ?, ?, ?, NOW(6), NOW(6))
				""", subject, "Person " + subject, showOrganisation);
		return jdbc.queryForObject("SELECT MAX(id) FROM value_set WHERE owner_subject = ?", Long.class, subject);
	}

	private void saveProfile(String subject, String organisation, boolean contactable) throws Exception {
		mvc.perform(put("/api/profile").with(signedInAs(subject)).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"organisation\": " + (organisation == null ? "null" : "\"" + organisation + "\"")
								+ ", \"contactable\": " + contactable + "}"))
				.andExpect(status().isOk());
	}

	@Test
	void aNewProfileIsEmptyAndShowsThePersonTheirOwnAddress() throws Exception {
		mvc.perform(get("/api/profile").with(signedInAs("olive")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Person olive"))
				.andExpect(jsonPath("$.organisation").value(nullValue()))
				.andExpect(jsonPath("$.contactable").value(false))
				.andExpect(jsonPath("$.accountEmail").value("olive@example.org"))
				.andExpect(jsonPath("$.avatarUrl").value(nullValue()));
	}

	@Test
	void theAddressIsStoredOnlyWhileMessagingIsOn() throws Exception {
		saveProfile("olive", "  Example   Health Trust ", true);
		assertThat(jdbc.queryForObject("SELECT contact_email FROM user_profile WHERE subject = 'olive'", String.class))
				.isEqualTo("olive@example.org");
		mvc.perform(get("/api/profile").with(signedInAs("olive")))
				.andExpect(jsonPath("$.organisation").value("Example Health Trust"))
				.andExpect(jsonPath("$.contactable").value(true));

		saveProfile("olive", "Example Health Trust", false);
		assertThat(jdbc.queryForObject("SELECT contact_email FROM user_profile WHERE subject = 'olive'", String.class))
				.isNull();
	}

	@Test
	void messagingCannotBeSwitchedOnWithoutAVerifiedAddress() throws Exception {
		mvc.perform(put("/api/profile").with(signedInWithoutEmail("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"contactable\": true}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(containsString("no verified email address")));
	}

	@Test
	void anUnverifiedAddressIsIgnored() throws Exception {
		mvc.perform(get("/api/profile").with(oidcLogin().idToken(token -> token.subject("olive")
						.claim("email", "someone-else@example.org").claim("email_verified", false))))
				.andExpect(jsonPath("$.accountEmail").value(nullValue()));
	}

	@Test
	void aPictureIsStoredAsAPngThatOnlySignedInPeopleCanSee() throws Exception {
		MockMultipartFile picture = new MockMultipartFile("file", "me.jpg", "image/jpeg", jpeg(600, 600));
		String avatarUrl = com.jayway.jsonpath.JsonPath.read(mvc.perform(multipart(HttpMethod.PUT, "/api/profile/avatar")
						.file(picture).with(signedInAs("olive")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.avatarUrl").value(startsWith("/api/avatars/")))
				.andReturn().getResponse().getContentAsString(), "$.avatarUrl");

		mvc.perform(get(avatarUrl).with(signedInAs("rita")))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG));
		mvc.perform(get(avatarUrl)).andExpect(status().isUnauthorized());

		mvc.perform(delete("/api/profile/avatar").with(signedInAs("olive")).with(csrf()))
				.andExpect(jsonPath("$.avatarUrl").value(nullValue()));
		mvc.perform(get(avatarUrl).with(signedInAs("rita"))).andExpect(status().isNotFound());
	}

	@Test
	void aFileThatIsNotAPictureIsRefused() throws Exception {
		MockMultipartFile svg = new MockMultipartFile("file", "me.svg", "image/svg+xml",
				"<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>".getBytes());
		mvc.perform(multipart(HttpMethod.PUT, "/api/profile/avatar").file(svg).with(signedInAs("olive")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aSetShowsTheOrganisationWhenItsOwnerChoseThatAndOthersCannotSeeTheChoice() throws Exception {
		saveProfile("olive", "Example Health Trust", false);
		long asOrganisation = communitySetOwnedBy("olive", true);
		long asPerson = communitySetOwnedBy("olive", false);

		mvc.perform(get("/api/value-sets/" + asOrganisation).with(signedInAs("rita")))
				.andExpect(jsonPath("$.ownerName").value("Example Health Trust"))
				.andExpect(jsonPath("$.ownerIsOrganisation").value(true))
				.andExpect(jsonPath("$.showOrganisation").value(false));
		mvc.perform(get("/api/value-sets/" + asPerson).with(signedInAs("rita")))
				.andExpect(jsonPath("$.ownerName").value("Person olive"));
		mvc.perform(get("/api/value-sets/" + asOrganisation).with(signedInAs("olive")))
				.andExpect(jsonPath("$.showOrganisation").value(true));
		mvc.perform(get("/api/value-sets"))
				.andExpect(jsonPath("$[0].ownerName").value(nullValue()));
	}

	@Test
	void theOwnerCanChooseTheirOrganisationWhenSavingASet() throws Exception {
		given(snowstorm.codeSystems()).willReturn(List.of(
				new CodeSystem("SNOMEDCT", "International Edition", "MAIN", "2026-01-01", "MAIN/2026-01-01")));
		given(snowstorm.expand(anyString(), anyString(), anyInt(), anyInt()))
				.willReturn(new ExpansionPage(List.of(), 0, 0, 100));
		saveProfile("olive", "Example Health Trust", false);
		mvc.perform(post("/api/value-sets").with(signedInAs("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Asthma", "description": "", "ecl": "<< 195967001",
								 "visibility": "PRIVATE", "showOrganisation": true}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.showOrganisation").value(true))
				.andExpect(jsonPath("$.ownerName").value("Example Health Trust"));
	}

	@Test
	void aMessageGoesToTheOwnerWithTheSenderAsReplyToAndIsNotStored() throws Exception {
		saveProfile("olive", null, true);
		long id = communitySetOwnedBy("olive", false);
		mvc.perform(get("/api/value-sets/" + id).with(signedInAs("rita")))
				.andExpect(jsonPath("$.ownerContactable").value(true))
				.andExpect(content().string(not(containsString("olive@example.org"))));

		mvc.perform(post("/api/value-sets/" + id + "/messages").with(signedInAs("rita")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
				.andExpect(status().isNoContent());

		ArgumentCaptor<OwnerMessageEmail> sent = ArgumentCaptor.forClass(OwnerMessageEmail.class);
		verify(resend).send(sent.capture());
		assertThat(sent.getValue().to()).isEqualTo("olive@example.org");
		assertThat(sent.getValue().replyTo()).isEqualTo("rita@example.org");
		assertThat(sent.getValue().text()).contains("Could you add occupational asthma?")
				.contains("https://sets.example.org/value-sets/" + id);
		assertThat(jdbc.queryForList("SELECT * FROM owner_message").getFirst())
				.containsOnlyKeys("id", "sender_subject", "value_set_id", "sent_at");
	}

	@Test
	void anOwnerWhoDoesNotAllowMessagesCannotBeMessaged() throws Exception {
		long id = communitySetOwnedBy("olive", false);
		mvc.perform(get("/api/value-sets/" + id).with(signedInAs("rita")))
				.andExpect(jsonPath("$.ownerContactable").value(false));
		mvc.perform(post("/api/value-sets/" + id + "/messages").with(signedInAs("rita")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
				.andExpect(status().isNotFound());
		verify(resend, never()).send(any());
	}

	@Test
	void visitorsAndOwnersCannotSendMessages() throws Exception {
		saveProfile("olive", null, true);
		long id = communitySetOwnedBy("olive", false);
		mvc.perform(post("/api/value-sets/" + id + "/messages").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/value-sets/" + id + "/messages").with(signedInAs("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
				.andExpect(status().isBadRequest());
		verify(resend, never()).send(any());
	}

	@Test
	void aSenderWithoutAVerifiedAddressIsToldTheOwnerCouldNotReply() throws Exception {
		saveProfile("olive", null, true);
		long id = communitySetOwnedBy("olive", false);
		mvc.perform(post("/api/value-sets/" + id + "/messages").with(signedInWithoutEmail("rita")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
				.andExpect(status().isConflict());
	}

	@Test
	void sendingIsLimitedAndAFailedSendDoesNotCount() throws Exception {
		saveProfile("olive", null, true);
		long id = communitySetOwnedBy("olive", false);

		willThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY)).given(resend).send(any());
		mvc.perform(post("/api/value-sets/" + id + "/messages").with(signedInAs("rita")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
				.andExpect(status().isBadGateway());
		org.mockito.Mockito.reset(resend);

		for (int i = 0; i < 5; i++) {
			mvc.perform(post("/api/value-sets/" + id + "/messages").with(signedInAs("rita")).with(csrf())
							.contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
					.andExpect(status().isNoContent());
		}
		mvc.perform(post("/api/value-sets/" + id + "/messages").with(signedInAs("rita")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void aMessageMustHaveSomethingToSay() throws Exception {
		saveProfile("olive", null, true);
		long id = communitySetOwnedBy("olive", false);
		mvc.perform(post("/api/value-sets/" + id + "/messages").with(signedInAs("rita")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
				.andExpect(status().isBadRequest());
	}

	private static byte[] jpeg(int width, int height) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", out);
		return out.toByteArray();
	}
}
