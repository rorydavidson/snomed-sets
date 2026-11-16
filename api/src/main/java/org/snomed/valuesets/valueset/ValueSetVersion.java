package org.snomed.valuesets.valueset;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The definition of a value set as it was when a version was published. Never changed afterwards. */
@Entity
@Table(name = "value_set_version")
public class ValueSetVersion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "value_set_id", nullable = false)
	private Long valueSetId;

	@Column(name = "version_number", nullable = false)
	private int versionNumber;

	@Column(nullable = false, length = 4000)
	private String ecl;

	@Column(nullable = false, length = 200)
	private String branch;

	/** How many concepts the definition matched when it was published. */
	@Column(name = "concept_count", nullable = false)
	private long conceptCount;

	@Column(nullable = false, length = 500)
	private String note;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	protected ValueSetVersion() {
		// for JPA
	}

	public ValueSetVersion(ValueSet valueSet, int versionNumber, long conceptCount, String note) {
		this.valueSetId = valueSet.getId();
		this.versionNumber = versionNumber;
		this.ecl = valueSet.getEcl();
		this.branch = valueSet.getBranch();
		this.conceptCount = conceptCount;
		this.note = note;
		this.publishedAt = Instant.now();
	}

	public boolean hasSameDefinitionAs(ValueSet valueSet) {
		return ecl.equals(valueSet.getEcl()) && branch.equals(valueSet.getBranch());
	}

	public Long getValueSetId() {
		return valueSetId;
	}

	public int getVersionNumber() {
		return versionNumber;
	}

	public String getEcl() {
		return ecl;
	}

	public String getBranch() {
		return branch;
	}

	public long getConceptCount() {
		return conceptCount;
	}

	public String getNote() {
		return note;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}
}
