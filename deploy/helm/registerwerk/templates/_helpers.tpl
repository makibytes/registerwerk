{{/*
Expand the name of the chart.
*/}}
{{- define "registerwerk.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "registerwerk.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "registerwerk.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "registerwerk.labels" -}}
helm.sh/chart: {{ include "registerwerk.chart" . }}
{{ include "registerwerk.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "registerwerk.selectorLabels" -}}
app.kubernetes.io/name: {{ include "registerwerk.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "registerwerk.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "registerwerk.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Component-scoped naming/labels for the second (and further) Deployments this chart now ships
(frontend-operator, frontend-customer, zama-relayer) alongside the original backend Deployment.

registerwerk.selectorLabels (above) is release-wide, not per-workload: app.kubernetes.io/name +
app.kubernetes.io/instance only. It was fine when this chart had exactly one Deployment, but reusing
it verbatim for a second Deployment would give both workloads byte-identical selector labels — their
Services would round-robin traffic across BOTH Deployments' pods (a Service's selector has no concept
of "this Deployment specifically", it just matches labels), and a `matchLabels` topologySpreadConstraint
or PDB on one would silently also govern the other's pods. Every new template must use the
"component" variants below, never the bare `registerwerk.*` helpers, for its selector labels.

Usage: {{ include "registerwerk.componentFullname" (dict "context" . "component" "frontend-operator") }}
(a `dict` wrapper, not a second positional arg, because named Helm templates only ever receive one
context value — this is the standard subchart-style workaround.)
*/}}
{{- define "registerwerk.componentFullname" -}}
{{- printf "%s-%s" (include "registerwerk.fullname" .context) .component | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "registerwerk.componentSelectorLabels" -}}
{{ include "registerwerk.selectorLabels" .context }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "registerwerk.componentLabels" -}}
helm.sh/chart: {{ include "registerwerk.chart" .context }}
{{ include "registerwerk.componentSelectorLabels" . }}
app.kubernetes.io/version: {{ .context.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .context.Release.Service }}
{{- end }}
