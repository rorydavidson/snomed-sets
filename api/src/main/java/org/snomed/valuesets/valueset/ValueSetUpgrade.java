package org.snomed.valuesets.valueset;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One move of a value set onto a newer release of its code system. Never changed afterwards. */
@Entity
@Table(name = "value_set_upgrade")
public class ValueSetUpgrade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "value_set_id", nullable = false)
	private Long valueSetId;

	/** Null when the value set was on the live branch rather than a release. */
	@Column(name = "from_version", length = 10)
	private String fromVersion;

	@Column(name = "to_version", nullable = false, length = 10)
	private String toVersion;

	@Column(name = "from_branch", nullable = false, length = 200)
	private String fromBranch;

	@Column(name = "to_branch", nullable = false, length = 200)
	private String toBranch;

	/** The version published together with the upgrade, or null if it was saved as a draft. */
	@Column(name = "published_version")
	private Integer publishedVersion;

	@Column(name = "upgraded_at", nullable = false)
	private Instant upgradedAt;

	protected ValueSetUpgrade() {
		// for JPA
	}

	ValueSetUpgrade(long valueSetId, String fromBranch, String toBranch, Integer publishedVersion) {
		this.valueSetId = valueSetId;
		this.fromBranch = fromBranch;
		this.fromVersion = ValueSet.releaseOf(fromBranch);
		this.toBranch = toBranch;
		this.toVersion = ValueSet.releaseOf(toBranch);
		this.publishedVersion = publishedVersion;
		this.upgradedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Long getValueSetId() {
		return valueSetId;
	}

	public String getFromVersion() {
		return fromVersion;
	}

	public String getToVersion() {
		return toVersion;
	}

	public String getFromBranch() {
		return fromBranch;
	}

	public String getToBranch() {
		return toBranch;
	}

	public Integer getPublishedVersion() {
		return publishedVersion;
	}

	public Instant getUpgradedAt() {
		return upgradedAt;
	}
}
