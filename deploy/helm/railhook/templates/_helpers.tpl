{{- define "railhook.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

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

{{- define "railhook.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "railhook.labels" -}}
helm.sh/chart: {{ include "railhook.chart" . }}
{{ include "railhook.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "railhook.selectorLabels" -}}
app.kubernetes.io/name: {{ include "railhook.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "railhook.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "railhook.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "railhook.database.host" -}}
{{- if .Values.postgresql.enabled }}
{{- printf "%s-postgresql" (include "railhook.fullname" .) }}
{{- else }}
{{- .Values.postgresql.external.host }}
{{- end }}
{{- end }}

{{- define "railhook.database.port" -}}
{{- if .Values.postgresql.enabled }}
{{/* dig: the subchart-shaped keys are absent unless a caller supplies them. */}}
{{- dig "primary" "service" "ports" "postgresql" 5432 .Values.postgresql }}
{{- else }}
{{- default 5432 .Values.postgresql.external.port }}
{{- end }}
{{- end }}

{{- define "railhook.kafka.bootstrapServers" -}}
{{- if .Values.kafka.enabled }}
{{- printf "%s-kafka:9092" (include "railhook.fullname" .) }}
{{- else }}
{{- .Values.kafka.external.bootstrapServers }}
{{- end }}
{{- end }}

{{- define "railhook.redis.host" -}}
{{- if .Values.redis.enabled }}
{{- printf "%s-redis-master" (include "railhook.fullname" .) }}
{{- else }}
{{- .Values.redis.external.host }}
{{- end }}
{{- end }}

{{- define "railhook.redis.port" -}}
{{- if .Values.redis.enabled }}
{{/* dig: the subchart-shaped keys are absent unless a caller supplies them. */}}
{{- dig "master" "service" "ports" "redis" 6379 .Values.redis }}
{{- else }}
{{- default 6379 .Values.redis.external.port }}
{{- end }}
{{- end }}

{{/*
Actuator has its own port (on the main one /actuator/prometheus answers 401). Not a value: env,
containerPort and the ServiceMonitor's Service port must agree with docker-compose.yml.
*/}}
{{- define "railhook.api.managementPort" -}}8082{{- end }}
{{- define "railhook.worker.managementPort" -}}8081{{- end }}

{{/*
The URL people type; mail links are built from it. Falls back to the UI ingress host, then the
in-cluster UI Service — never the application's own http://localhost:5173 default.
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
