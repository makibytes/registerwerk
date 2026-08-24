# Registerwerk Helm deployment

This chart deploys Registerwerk and its bundled infrastructure. Chaincache is intentionally not a
subchart: install Chaincache independently once per chain and enable only the network-policy/secret
integration under `chaincache.*` here.

## Database choices

Exactly one database path must be selected:

- `postgresql.enabled=true` installs the Bitnami PostgreSQL subchart and wires its existing Secret.
- `cloudSqlProxy.enabled=true` uses the Cloud SQL Auth Proxy as a Kubernetes native sidecar init
  container. This requires Kubernetes 1.29 or later. Disable `postgresql.enabled`; chart rendering
  fails if both are enabled.

For GKE Workload Identity Federation, annotate `serviceAccount.annotations` with
`iam.gke.io/gcp-service-account`, set `serviceAccount.automountServiceAccountToken=true`, and grant
that Google service account `roles/cloudsql.client`. Enable `privateIp` only when private network
connectivity is configured. `autoIamAuthn` also requires IAM database authentication and a matching
database user; otherwise leave it false and supply the password Secret named by
`cloudSqlProxy.secretName`/`passwordKey`.

## Availability and pod security

The chart uses rolling updates with zero unavailable pods, a startup probe distinct from liveness,
readiness-based Service membership, graceful Spring shutdown, a pre-stop drain delay, and topology
spreading. The backend runs non-root with a read-only root filesystem, `RuntimeDefault` seccomp,
and all Linux capabilities dropped. Writable `/tmp` and `/app/cds` paths are isolated `emptyDir`
volumes. Keep the termination grace period longer than pre-stop plus Spring's shutdown timeout.

With HPA enabled, the Deployment omits `spec.replicas` so Helm upgrades do not fight the
autoscaler. The PodDisruptionBudget uses `unhealthyPodEvictionPolicy: AlwaysAllow` to let node
drains remove already-unhealthy pods while preserving healthy availability.

## Monitoring

Generic clusters can scrape the `prometheus.io/*` pod annotations. Set
`monitoring.googleManagedPrometheus=true` on GKE to render a `monitoring.googleapis.com/v1`
`PodMonitoring`; the Managed Service for Prometheus CRD must already exist. `/actuator/prometheus`
is deliberately unauthenticated inside this chart's network boundary, so do not expose the
actuator path through a public ingress.

## Validation

```bash
helm lint deploy/helm/registerwerk
helm template registerwerk deploy/helm/registerwerk >/dev/null

# GKE/Cloud SQL path: site values must disable the subchart and provide the instance name.
helm template registerwerk deploy/helm/registerwerk \
  --set postgresql.enabled=false \
  --set cloudSqlProxy.enabled=true \
  --set cloudSqlProxy.instanceConnectionName=project:region:instance >/dev/null
```

Also run `docker compose --profile docs config -q` when changing deployment/docs wiring.
