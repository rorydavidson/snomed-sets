package org.snomed.valuesets.valueset;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "value_set")
public class ValueSet {

	public enum Status { DRAFT, PUBLISHED }

	public enum Visibility { PRIVATE, COMMUNITY }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(nullable = false, length = 2000)
	private String description;

	@Column(nullable = false, length = 4000)
	private String ecl;

	/** Snowstorm branch path the definition is evaluated against, for example MAIN. */
	@Column(nullable = false, length = 200)
	private String branch;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Visibility visibility;

	/** The Keycloak subject of the owner. Used for access decisions, never shown. */
	@Column(name = "owner_subject", nullable = false)
	private String ownerSubject;

	@Column(name = "owner_name", nullable = false)
	private String ownerName;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ValueSet() {
		// for JPA
	}

	/** A new value set always starts as a draft. */
	public ValueSet(String name, String description, String ecl, String branch, Visibility visibility,
			String ownerSubject, String ownerName) {
		this.status = Status.DRAFT;
		this.ownerSubject = ownerSubject;
		this.ownerName = ownerName;
		this.createdAt = Instant.now();
		update(name, description, ecl, branch, visibility);
	}

	public void update(String name, String description, String ecl, String branch, Visibility visibility) {
		this.name = name;
		this.description = description;
		this.ecl = ecl;
		this.branch = branch;
		this.visibility = visibility;
		this.updatedAt = Instant.now();
	}

	public boolean isOwnedBy(String subject) {
		return ownerSubject.equals(subject);
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public String getEcl() {
		return ecl;
	}

	public String getBranch() {
		return branch;
	}

	public Status getStatus() {
		return status;
	}

	public Visibility getVisibility() {
		return visibility;
	}

	public String getOwnerSubject() {
		return ownerSubject;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
