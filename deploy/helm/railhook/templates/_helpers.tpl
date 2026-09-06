{{/*
Expand the name of the chart.
*/}}
{{- define "railhook.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "railhook.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "railhook.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "railhook.labels" -}}
helm.sh/chart: {{ include "railhook.chart" . }}
{{ include "railhook.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "railhook.selectorLabels" -}}
app.kubernetes.io/name: {{ include "railhook.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "railhook.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "railhook.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Database host
*/}}
{{- define "railhook.database.host" -}}
{{- if .Values.postgresql.enabled }}
{{- printf "%s-postgresql" (include "railhook.fullname" .) }}
{{- else }}
{{- .Values.postgresql.external.host }}
{{- end }}
{{- end }}

{{/*
Database port
*/}}
{{- define "railhook.database.port" -}}
{{- if .Values.postgresql.enabled }}
{{/* dig (not plain dot-chaining) because this chart no longer ships
     a postgresql subchart - primary.service.ports.postgresql only has a
     value if a caller supplies their own subchart-shaped override, and dig
     tolerates the (now-default) case where those keys are absent entirely. */}}
{{- dig "primary" "service" "ports" "postgresql" 5432 .Values.postgresql }}
{{- else }}
{{- default 5432 .Values.postgresql.external.port }}
{{- end }}
{{- end }}

{{/*
Kafka bootstrap servers
*/}}
{{- define "railhook.kafka.bootstrapServers" -}}
{{- if .Values.kafka.enabled }}
{{- printf "%s-kafka:9092" (include "railhook.fullname" .) }}
{{- else }}
{{- .Values.kafka.external.bootstrapServers }}
{{- end }}
{{- end }}

{{/*
Redis host
*/}}
{{- define "railhook.redis.host" -}}
{{- if .Values.redis.enabled }}
{{- printf "%s-redis-master" (include "railhook.fullname" .) }}
{{- else }}
{{- .Values.redis.external.host }}
{{- end }}
{{- end }}

{{/*
Redis port
*/}}
{{- define "railhook.redis.port" -}}
{{- if .Values.redis.enabled }}
{{/* dig, for the same reason as railhook.database.port above - this
     chart no longer ships a redis subchart. */}}
{{- dig "master" "service" "ports" "redis" 6379 .Values.redis }}
{{- else }}
{{- default 6379 .Values.redis.external.port }}
{{- end }}
{{- end }}

{{/*
Actuator ("management") port for each service.

Both apps read MANAGEMENT_PORT and serve /actuator/** on it (see each
application.yml: `management.server.port`). Splitting actuator off the main
port is what lets Prometheus scrape /actuator/prometheus at all: on the main
port that path goes through SecurityConfig's authenticated filter chain and
answers 401, so every scrape failed and the PrometheusRule alerts below fired
on absent data rather than on anything real. The numbers match
docker-compose.yml (8082 for api, 8081 for worker) so the two deployment
paths cannot drift - that drift has already been a live metrics bug once.

Declared here rather than in values.yaml because three things have to agree
(the container's env, its containerPort, and the Service port the
ServiceMonitor names by name), and a knob is a way for them to disagree.
*/}}
{{- define "railhook.api.managementPort" -}}8082{{- end }}
{{- define "railhook.worker.managementPort" -}}8081{{- end }}

{{/*
The URL people type into a browser.

Verification, password-reset and invite links are built from it, so a wrong
value produces mail whose links nobody can follow - and the application's own
default is http://localhost:5173, which is exactly that. install.sh asks for
this and writes APP_BASE_URL (and CORS_ALLOWED_ORIGINS from the same value);
this is the chart's equivalent, defaulting to the UI ingress host - https when
TLS is configured - since that is the address the ingress already promises.
Set app.baseUrl explicitly when browsers reach Railhook by some other name.

The last resort, the in-cluster UI Service, is not reachable from a mailbox,
but it is at least this release's own address rather than the developer's
laptop.
*/}}
{{- define "railhook.appBaseUrl" -}}
{{- if .Values.app.baseUrl }}
{{- .Values.app.baseUrl | trimSuffix "/" }}
{{- else if and .Values.ui.ingress.enabled .Values.ui.ingress.hosts }}
{{- $scheme := ternary "https" "http" (not (empty .Values.ui.ingress.tls)) }}
{{- printf "%s://%s" $scheme (first .Values.ui.ingress.hosts).host }}
{{- else }}
{{- printf "http://%s-ui" (include "railhook.fullname" .) }}
{{- end }}
{{- end }}
