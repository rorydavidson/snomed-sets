package org.snomed.valuesets.valueset;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.snomed.valuesets.profile.OwnerProfile;
import org.snomed.valuesets.user.CurrentUser;
import org.springframework.test.util.ReflectionTestUtils;

class ValueSetViewTests {

	private static final CurrentUser OWNER = new CurrentUser("owner-subject", "Olive Owner");
	private static final CurrentUser READER = new CurrentUser("reader-subject", "Rita Reader");
	private static final OwnerProfile PROFILE = new OwnerProfile("owner-subject", "6f1c6f1c-6f1c-6f1c-6f1c-6f1c6f1c6f1c",
			"Example Health Trust", true, Instant.ofEpochMilli(1000));

	@Test
	void showsTheOrganisationInsteadOfTheOwnerWhenTheyChoseIt() {
		ValueSetView view = ValueSetView.of(valueSet(true), null, false, READER, PROFILE, true);

		assertThat(view.ownerName()).isEqualTo("Example Health Trust");
		assertThat(view.ownerIsOrganisation()).isTrue();
		assertThat(view.ownerAvatarUrl()).isEqualTo("/api/avatars/6f1c6f1c-6f1c-6f1c-6f1c-6f1c6f1c6f1c?v=1000");
		// The choice itself is only the owner's business.
		assertThat(view.showOrganisation()).isFalse();
	}

	@Test
	void showsTheOwnersNameWhenTheyChoseTheOrganisationButHaveNone() {
		OwnerProfile noOrganisation = new OwnerProfile("owner-subject", "id", null, false, null);

		ValueSetView view = ValueSetView.of(valueSet(true), null, false, OWNER, noOrganisation, true);

		assertThat(view.ownerName()).isEqualTo("Olive Owner");
		assertThat(view.ownerIsOrganisation()).isFalse();
		assertThat(view.showOrganisation()).isTrue();
	}

	@Test
	void offersMessagingOnlyToOthersWhenTheOwnerAllowsItAndItIsSetUp() {
		assertThat(ValueSetView.of(valueSet(false), null, false, READER, PROFILE, true).ownerContactable()).isTrue();
		assertThat(ValueSetView.of(valueSet(false), null, false, OWNER, PROFILE, true).ownerContactable()).isFalse();
		assertThat(ValueSetView.of(valueSet(false), null, false, READER, PROFILE, false).ownerContactable()).isFalse();
		assertThat(ValueSetView.of(valueSet(false), null, false, CurrentUser.ANONYMOUS, PROFILE, true).ownerContactable())
				.isFalse();
	}

	@Test
	void tellsVisitorsNothingAboutTheOwner() {
		ValueSetView view = ValueSetView.of(valueSet(true), null, false, CurrentUser.ANONYMOUS, PROFILE, true);

		assertThat(view.ownerName()).isNull();
		assertThat(view.ownerAvatarUrl()).isNull();
		assertThat(view.ownerIsOrganisation()).isFalse();
	}

	private static ValueSet valueSet(boolean showOrganisation) {
		ValueSet valueSet = new ValueSet(ValueSet.DefinitionType.ECL, "SNOMEDCT", "MAIN/2026-01-01", "Asthma diagnoses", "",
				"<< 195967001", ValueSet.Visibility.COMMUNITY, List.of(), "owner-subject", "Olive Owner",
				showOrganisation);
		// As if saved, which is when the id is given.
		ReflectionTestUtils.setField(valueSet, "id", 7L);
		return valueSet;
	}
}
