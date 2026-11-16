package org.snomed.valuesets.valueset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.snomed.valuesets.snowstorm.ExpansionPage;

/**
 * One concept of a hand-picked value set. The terms are copied from Snowstorm when the
 * concept is added, so the list can be shown without calling Snowstorm.
 */
@Entity
@Table(name = "value_set_member")
public class ValueSetMember {

	/** The version number used for the list that is still being edited. */
	public static final int WORKING_LIST = 0;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "value_set_id", nullable = false)
	private Long valueSetId;

	@Column(name = "version_number", nullable = false)
	private int versionNumber;

	@Column(name = "concept_id", nullable = false, length = 18)
	private String conceptId;

	@Column(name = "preferred_term", nullable = false, length = 500)
	private String preferredTerm;

	@Column(name = "fully_specified_name", nullable = false, length = 500)
	private String fullySpecifiedName;

	protected ValueSetMember() {
		// for JPA
	}

	public ValueSetMember(long valueSetId, ExpansionPage.Concept concept) {
		this.valueSetId = valueSetId;
		this.versionNumber = WORKING_LIST;
		this.conceptId = concept.conceptId();
		this.preferredTerm = concept.preferredTerm() == null ? "" : concept.preferredTerm();
		this.fullySpecifiedName = concept.fullySpecifiedName() == null ? "" : concept.fullySpecifiedName();
	}

	public ExpansionPage.Concept toConcept() {
		return new ExpansionPage.Concept(conceptId, preferredTerm, fullySpecifiedName);
	}

	public String getConceptId() {
		return conceptId;
	}
}
