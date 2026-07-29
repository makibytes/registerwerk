---
title: DORA — ICT Risk Management
description: Prototype ICT incident, resilience-test, and third-party-provider records; not a complete DORA implementation.
---

# DORA — Digital Operational Resilience Act

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page records intended control mappings and current repository behavior. It is not legal
    advice or evidence that DORA applies to a particular operator, that a complete DORA control
    framework exists, or that an incident has been validly classified or reported. Applicability,
    classification, deadlines, competent authorities, forms, channels, and evidence require a
    current operator-, service-, incident-, jurisdiction-, and deployment-specific review by
    qualified counsel and the responsible resilience and compliance owners.

The repository contains a manual operational record for ICT incidents, resilience tests, and
third-party providers. It is not an authority-reporting implementation.

## Scope and applicability

DORA applicability cannot be inferred from the repository name, an `eWpG` jurisdiction value, a
token standard, or the presence of a `dora` module. The operator's regulated capacities and the
services actually performed must be classified externally before relying on any control mapping.

Current-law statements about DORA articles, technical standards, classification thresholds, and
reporting deadlines must be checked against current official sources as part of that review.

## Current incident record

An authorised operator can manually create an `IctIncident` through
`POST /api/v1/dora/incidents`. The current entity records:

- category: `DATA_BREACH`, `SYSTEM_OUTAGE`, `RANSOMWARE`, `THIRD_PARTY_FAILURE`, or `OTHER`;
- severity: `LOW`, `MEDIUM`, `HIGH`, or `MAJOR`;
- status: `DETECTED`, `INVESTIGATING`, `CONTAINED`, `RESOLVED`,
  `REPORTED_TO_AUTHORITY`, or `CLOSED`;
- description, source-event labels, timestamps, root cause, remediation, assignment, and an
  operator-entered authority reference;
- application-calculated reminder timestamps for incidents entered as `MAJOR`.

These values are operator-entered operational data. A status such as `REPORTED_TO_AUTHORITY` or
an `authorityRef` records an operator assertion; the application does not independently verify an
authority receipt or acceptance.

## Deadline monitoring

`DoraService` runs a daily job that queries overdue application deadlines and writes log messages.
It also exposes gauges for overdue records. The job does not submit a notification, create an
authority-formatted report, prove that the configured deadline is legally correct, or notify all
responsible personnel.

The current model does not represent a complete initial/intermediate/final reporting workflow.
Operators must not use its timestamps as statutory deadlines without current legal and regulatory
review.

## Automatic incident detection — not implemented

Internal audit, chain-drift, indexer, RPC, or screening events are not automatically classified
and converted into `IctIncident` records. `sourceEventType` and `sourceEventRef` are manually
supplied correlation fields, not evidence of an automated detection pipeline.

## ICT third-party records

The `ThirdPartyProvider` entity stores operational fields including name, category, criticality,
LEI, country, contract dates, sub-outsourcing notes, contact, SLA, RTO/RPO, and an
operator-maintained notification flag. Records are listed through:

- `GET /api/v1/dora/providers`
- `GET /api/v1/dora/providers/expiring`

This table is not a complete or authority-approved DORA register of information. No
authority-ready, schema-validated Art. 28 export is implemented.

## Resilience-test records

The module can record and list resilience-test metadata and highlight records whose configured
next-due date has passed. It does not execute a resilience test, validate its evidence, establish
TLPT scope, or certify the result.

## Authority routing and filing — not implemented

The repository does not implement jurisdiction-specific DORA authority routing, official forms or
schemas, authenticated transmission, delivery receipts, corrections, rejection handling, or
authority acceptance. Recording that an incident was reported is not filing evidence.
