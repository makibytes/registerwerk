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
is deliberately unauthenticated inside this chart's network boundary; the default ingress blocks
the actuator path (see below).

## Ingress, Kong and the client IP

The API ingress (`ingress.*`) targets Kong's proxy Service (`<release>-kong-proxy`), never the
backend Service. With `kong.enabled=false` the chart refuses to render unless you name a Service in
`ingress.backendService`. Three inputs have no default and make the chart fail to render until you
set them:

- `ingress.trustedCidrs` — the ingress controller's pod CIDRs (the frontend nginx pods restore the
  real client address from `X-Forwarded-For` only from these).
- `kong.env.trusted_ips` — the CIDRs whose `X-Forwarded-For` Kong believes (ingress controller and
  customer-frontend pods). `real_ip_recursive` stays off because those hops overwrite the header.
- `kong.adminAllowCidrs` — the operator networks allowed on `/api/v1/admin/**`; `files/kong.yml` is
  rendered from it.

Set `env.REGISTERWERK_AUTH_TRUSTED_PROXIES` to a regex of your pod CIDR so the backend's login
throttle sees the real client behind Kong.

### Blocking /actuator at the ingress

Only `/actuator/health*` may be reachable on the public API host. The default
`ingress.annotations` carry an ingress-nginx `server-snippet` that answers 404 for
`^/actuator/(?!health)` (ingress-nginx needs `allow-snippet-annotations: "true"`). For another
controller, add the equivalent rule: Traefik/Envoy Gateway/GKE Gateway users should add a
path-match on `/actuator` (excluding `/actuator/health`) that returns 404, or a request filter, in
the controller's own configuration. Prometheus scrapes `:8080/actuator/prometheus` inside the
cluster (annotations or `PodMonitoring`), never through the ingress; the backend NetworkPolicy
admits only Kong and Prometheus.

## HSM / KMS hooks

`extraVolumes`, `extraVolumeMounts`, `extraEnv`, `extraEnvFrom`, `initContainers` and `sidecars`
are generic, empty-by-default hooks on the backend Deployment, for mounting a PKCS#11 config and a
vendor HSM client library or supplying KEK settings. No vendor client is bundled and SoftHSM is
demo-only: in production mode the backend refuses to start until an HSM/KMS target is chosen
(parked decision T7-05). Multi-replica deployments need a shared signer (network HSM or KMS).
`values-production.yaml` shows a commented network-HSM pattern.

## Validation

```bash
cd deploy/helm/registerwerk
helm lint . -f ci/test-values.yaml
helm template registerwerk . -f ci/test-values.yaml >/dev/null

# GKE/Cloud SQL path: site values must disable the subchart and provide the instance name.
helm template registerwerk . -f ci/test-values.yaml \
  --set postgresql.enabled=false \
  --set cloudSqlProxy.enabled=true \
  --set cloudSqlProxy.instanceConnectionName=project:region:instance >/dev/null
```

Also run `docker compose --profile docs config -q` when changing deployment/docs wiring.
