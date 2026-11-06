{{/* Имя релиза, обрезанное до 63 символов по требованиям DNS. */}}
{{- define "easyitsm.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "easyitsm.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: easyitsm
{{- end -}}

{{/* Метки выбора компонента. Вызов: include "easyitsm.selector" (list . "api") */}}
{{- define "easyitsm.selector" -}}
{{- $root := index . 0 -}}
app.kubernetes.io/name: {{ $root.Chart.Name }}
app.kubernetes.io/instance: {{ $root.Release.Name }}
app.kubernetes.io/component: {{ index . 1 }}
{{- end -}}

{{/*
Ссылка на образ. Digest важнее тега: подпись проверяется по digest (SEC-38).
global.imageRegistry подменяет реестр для офлайн-установки из зеркала заказчика.
Вызов: include "easyitsm.image" (list . .Values.image.core)
*/}}
{{- define "easyitsm.image" -}}
{{- $root := index . 0 -}}
{{- $img := index . 1 -}}
{{- $repo := $img.repository -}}
{{- with $root.Values.global.imageRegistry -}}
{{- $parts := splitn "/" 2 $repo -}}
{{- if or (contains "." $parts._0) (contains ":" $parts._0) (eq $parts._0 "localhost") -}}
{{- $repo = printf "%s/%s" . $parts._1 -}}
{{- else -}}
{{- $repo = printf "%s/%s" . $repo -}}
{{- end -}}
{{- end -}}
{{- if $img.digest -}}
{{- printf "%s@%s" $repo $img.digest -}}
{{- else -}}
{{- printf "%s:%s" $repo (default $root.Chart.AppVersion $img.tag) -}}
{{- end -}}
{{- end -}}

{{- define "easyitsm.serviceAccountName" -}}
{{- default (include "easyitsm.fullname" .) .Values.serviceAccount.name -}}
{{- end -}}

{{- define "easyitsm.dbSecretName" -}}
{{- default (printf "%s-db" (include "easyitsm.fullname" .)) .Values.database.existingSecret -}}
{{- end -}}

{{- define "easyitsm.appSecretName" -}}
{{- default (printf "%s-app" (include "easyitsm.fullname" .)) .Values.appSecret.existingSecret -}}
{{- end -}}

{{- define "easyitsm.dbHost" -}}
{{- if .Values.postgresql.enabled -}}
{{- printf "%s-postgresql" (include "easyitsm.fullname" .) -}}
{{- else -}}
{{- required "database.host обязателен, если встроенный PostgreSQL выключен" .Values.database.host -}}
{{- end -}}
{{- end -}}

{{/* Контекст безопасности пода по профилю Pod Security restricted (SEC-34). */}}
{{- define "easyitsm.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ .uid }}
runAsGroup: {{ .uid }}
fsGroup: {{ .uid }}
fsGroupChangePolicy: OnRootMismatch
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "easyitsm.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
privileged: false
capabilities:
  drop: ["ALL"]
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "easyitsm.imagePullSecrets" -}}
{{- with .Values.global.imagePullSecrets }}
imagePullSecrets:
{{- range . }}
  - name: {{ . }}
{{- end }}
{{- end }}
{{- end -}}

{{/* Адрес clamd для ядра: явный или встроенный сервис чарта. */}}
{{- define "easyitsm.clamavHost" -}}
{{- if .Values.attachments.antivirus.host -}}
{{- .Values.attachments.antivirus.host -}}
{{- else if .Values.clamav.enabled -}}
{{- printf "%s-clamav" (include "easyitsm.fullname" .) -}}
{{- else -}}
{{- fail "attachments.antivirus.host обязателен, если встроенный ClamAV выключен (или antivirus.mode=none)" -}}
{{- end -}}
{{- end -}}

{{/* Источник хранилища файлов для CSP веб-клиента: схема, хост и порт без пути. */}}
{{- define "easyitsm.filesOrigin" -}}
{{- $url := default .Values.attachments.s3.publicEndpoint .Values.web.filesOrigin -}}
{{- if $url -}}
{{- $u := urlParse $url -}}
{{- printf "%s://%s" $u.scheme $u.host -}}
{{- end -}}
{{- end -}}
