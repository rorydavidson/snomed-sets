package org.snomed.valuesets.valueset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One line of an upgrade report. */
@Entity
@Table(name = "value_set_upgrade_change")
public class ValueSetUpgradeChange {

	public enum Type {
		/** An ECL definition matches this concept in the new release and did not before. */
		ADDED,
		/** An ECL definition matched this concept before and no longer does. */
		REMOVED,
		/** A hand-picked concept is inactive in the new release, so it was taken out of the list. */
		INACTIVATED,
		/** A hand-picked concept is inactive in the new release, and the owner kept it, flagged as inactive. */
		INACTIVE_KEPT,
		/** A concept that replaces an inactive one, accepted and put in the list. */
		REPLACEMENT_ADDED,
		/** A concept that was suggested as a replacement, which the owner chose not to add. */
		REPLACEMENT_DECLINED,
		/** The preferred term of a concept that stays in the value set changed. */
		TERM_CHANGED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "upgrade_id", nullable = false)
	private Long upgradeId;

	@Enumerated(EnumType.STRING)
	@Column(name = "change_type", nullable = false, length = 30)
	private Type type;

	@Column(name = "concept_id", nullable = false, length = 18)
	private String conceptId;

	/** The preferred term; for a term change, the new one. */
	@Column(nullable = false, length = 500)
	private String term;

	/** For a replacement, the inactive concept it replaces. */
	@Column(name = "related_concept_id", length = 18)
	private String relatedConceptId;

	/** For a replacement, the term of the inactive concept; for a term change, the old term. */
	@Column(name = "related_term", length = 500)
	private String relatedTerm;

	/** The historical association, or for an inactivation the reason, where Snowstorm gives one. */
	@Column(length = 100)
	private String detail;

	protected ValueSetUpgradeChange() {
		// for JPA
	}

	ValueSetUpgradeChange(long upgradeId, UpgradePreview.Change change) {
		this.upgradeId = upgradeId;
		this.type = change.type();
		this.conceptId = change.conceptId();
		this.term = truncate(change.term());
		this.relatedConceptId = change.relatedConceptId();
		this.relatedTerm = change.relatedTerm() == null ? null : truncate(change.relatedTerm());
		this.detail = change.detail();
	}

	private static String truncate(String term) {
		if (term == null) {
			return "";
		}
		return term.length() <= 500 ? term : term.substring(0, 500);
	}

	UpgradePreview.Change toChange() {
		return new UpgradePreview.Change(type, conceptId, term, relatedConceptId, relatedTerm, detail);
	}

	public Long getUpgradeId() {
		return upgradeId;
	}

	public Type getType() {
		return type;
	}
}
