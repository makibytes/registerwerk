---
title: Cadres juridiques
description: Aperçu des quatre juridictions prises en charge et de leurs cadres réglementaires.
---

# Cadres juridiques {#legal-frameworks}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page enregistre les mappages de contrôle prévus et les hypothèses configurées. Elle ne constitue pas
    un conseil juridique ni une preuve de conformité, d'autorisation réglementaire, de certification ou d'effet
    juridique. L'applicabilité dépend de l'opérateur, du service, de l'instrument, de la transaction, de la
    juridiction et du déploiement, et doit être approuvée par un conseiller juridique qualifié et les
    responsables de contrôle concernés.

Registerwerk contient des composants de configuration et techniques destinés à prendre en charge les déploiements dans quatre juridictions européennes. Les tableaux ci-dessous sont des données d'examen et non des déterminations selon lesquelles une loi s'applique ou que chaque obligation a été mise en œuvre.

---

## Juridictions prises en charge {#supported-jurisdictions}

| Juridiction | Autorité compétente | Droit primaire | Cadre de jetons | Rétention | MiFIR | MiCAR |
|---|---|---|---|---|---|---|
| 🇩🇪 **Allemagne** | BaFin | eWpG / GwG | Kryptowertpapier (KryptoFAV) | 10 ans | Oui | Non (MiCAR Art. 2(3)) |
| 🇱🇺 **Luxembourg** | CSSF | CSSF Circ. 19/732 / Loi AML 2004 | Instruments de fonds basés sur DLT | 5 ans | Oui | Oui |
| 🇫🇷 **France** | AMF | Code Monétaire / Loi PACTE | Minibons / Titres financiers | 5 ans | Oui | Oui |
| 🇱🇮 **Liechtenstein** | FMA | TVTG 2020 / SPG | Jeton (fournisseur de services TT) | 10 ans | Via passeport | Oui |

---

## L'énumération `Jurisdiction` {#the-jurisdiction-enum}

Dans le code, chaque juridiction est représentée par l'énumération `Jurisdiction` dans le module `customer` :

```java
public enum Jurisdiction {
    DE_EWPG,   // Germany — eWpG
    LU_CSSF,   // Luxembourg — CSSF
    FR_AMF,    // France — AMF
    LI_TVTG    // Liechtenstein — TVTG
}
```

A `LegalEntity` transporte un seul `Jurisdiction` configuré. Le code utilise cette valeur pour les profils et flux de travail sélectionnés ; il ne s'agit pas d'une décision de classification d'instrument et ne prouve pas qu'une autorité reçoit un rapport ou qu'une période de conservation configurée est légalement correcte.

---

## Configuration par juridiction {#per-jurisdiction-configuration}

La classe `JurisdictionRequirementConfig` (`kyc/api/`) contient des hypothèses d'application pour le comportement sélectionné par juridiction. Ce n'est pas une source légale de vérité. Il produit un bean `JurisdictionProfile` par juridiction, contenant des valeurs configurées telles que :

- Types de documents KYC requis (voir [KYC & AML](../compliance/kyc-aml.md))
- Fournisseurs de filtrage des sanctions (OpenSanctions + Refinitiv World-Check en option)
- Seuil du bénéficiaire effectif (25 % dans les quatre juridictions)
- Cadence d'actualisation KYC (365 jours pour tous, avec surveillance renforcée pour le Luxembourg)
- Seuil Travel Rule (1 000 € dans l'ensemble)
- Autorité de surveillance pour les notifications d'incidents DORA

---

## Obligations communes {#common-obligations}

Le référentiel regroupe plusieurs composants techniques sous des rubriques de conformité communes. Leur présence n'établit pas qu'une obligation s'applique ou a été satisfaite :

| Obligation | Mise en œuvre | Référence |
|---|---|---|
| Vérification de l'identité du client | `KycDocument`, `NaturalPerson`, `BeneficialOwner` | [KYC & AML](../compliance/kyc-aml.md) |
| Surveillance AML continue | `KycMonitoringJob`, réexamen des sanctions | [Filtrage des sanctions](../compliance/sanctions-screening.md) |
| Travel Rule / IVMS-101 | `TravelRuleProtocolPort`, `Ivms101` | [Travel Rule](../compliance/travel-rule.md) |
| Intégrité du registre des valeurs mobilières | Chaîne de hachage inviolable `audit_event` | [Journal d'audit](../platform/audit-log.md) |
| Restrictions commerciales | `HolderBlock` (Sperrvermerk) | [Sperrvermerk](../compliance/sperrvermerk.md) |
| Gestion des incidents ICT | `IctIncident`, `ThirdPartyProvider` | [DORA](../compliance/dora.md) |
| Déclaration des transactions | `MifirReportingService` | [MiFIR](../compliance/mifir.md) |
| Déclaration fiscale sur les crypto-actifs | Module `regreporting` | [DAC8 / CARF](../compliance/dac8.md) |

---

## Explorer par juridiction {#explore-by-jurisdiction}

- [Allemagne — eWpG](ewpg.md)
- [Luxembourg — CSSF](cssf-lu.md)
- [France — AMF](amf-fr.md)
- [Liechtenstein — TVTG](tvtg-li.md)
