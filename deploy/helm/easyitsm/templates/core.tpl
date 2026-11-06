{{/*
Deployment ядра. Один образ, две роли (раздел 2 PLAN.md): api и worker.
Вызов: include "easyitsm.coreDeployment" (dict "root" . "role" "api" "cfg" .Values.api "workerEnabled" true)
*/}}
{{- define "easyitsm.coreDeployment" -}}
{{- $root := .root -}}
{{- $cfg := .cfg -}}
{{- $fullname := include "easyitsm.fullname" $root -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $fullname }}-{{ .role }}
  labels:
    {{- include "easyitsm.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ .role }}
spec:
  {{- if not (and (eq .role "api") $cfg.autoscaling.enabled) }}
  replicas: {{ $cfg.replicas }}
  {{- end }}
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      {{- include "easyitsm.selector" (list $root .role) | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "easyitsm.labels" $root | nindent 8 }}
        app.kubernetes.io/component: {{ .role }}
        easyitsm.ru/core: "true"
      annotations:
        checksum/config: {{ include (print $root.Template.BasePath "/configmap-core.yaml") $root | sha256sum }}
        prometheus.io/scrape: "true"
        prometheus.io/port: "8081"
        prometheus.io/path: /actuator/prometheus
    spec:
      serviceAccountName: {{ include "easyitsm.serviceAccountName" $root }}
      automountServiceAccountToken: false
      enableServiceLinks: false
      {{- include "easyitsm.imagePullSecrets" $root | nindent 6 }}
      securityContext:
        {{- include "easyitsm.podSecurityContext" (dict "uid" 65532) | nindent 8 }}
      terminationGracePeriodSeconds: 45
      containers:
        - name: core
          image: {{ include "easyitsm.image" (list $root $root.Values.image.core) }}
          imagePullPolicy: {{ $root.Values.image.pullPolicy }}
          securityContext:
            {{- include "easyitsm.containerSecurityContext" $root | nindent 12 }}
          envFrom:
            - configMapRef:
                name: {{ $fullname }}-core
          env:
            - name: EASYITSM_WORKER_ENABLED
              value: {{ .workerEnabled | quote }}
            # Приём писем по SMTP от почтового сервера организации (CHN-01) слушают только поды api.
            - name: EASYITSM_INBOUND_SMTP_ENABLED
              value: {{ and (eq .role "api") $root.Values.mail.inboundSmtp.enabled | quote }}
            - name: EASYITSM_INBOUND_SMTP_PORT
              value: {{ $root.Values.mail.inboundSmtp.port | quote }}
            {{- with $cfg.extraEnv }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
          ports:
            {{- if eq .role "api" }}
            - name: http
              containerPort: 8080
            {{- if $root.Values.mail.inboundSmtp.enabled }}
            - name: smtp
              containerPort: {{ $root.Values.mail.inboundSmtp.port }}
            {{- end }}
            {{- end }}
            - name: management
              containerPort: 8081
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            periodSeconds: 5
            failureThreshold: 60
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: management }
            periodSeconds: 5
            failureThreshold: 3
          lifecycle:
            preStop:
              # Даём балансировщику убрать под из выдачи до остановки. Без оболочки — встроенный sleep kubelet.
              sleep: { seconds: 5 }
          resources:
            {{- toYaml $cfg.resources | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            - name: secrets
              mountPath: /run/secrets/easyitsm
              readOnly: true
      volumes:
        - name: tmp
          emptyDir:
            sizeLimit: 512Mi
        # Секреты — файлами, а не переменными окружения (SEC-36). Spring читает их через configtree.
        - name: secrets
          projected:
            defaultMode: 0440
            sources:
              - secret:
                  name: {{ include "easyitsm.dbSecretName" $root }}
                  items:
                    - { key: app-username, path: EASYITSM_DB_APP_USER }
                    - { key: app-password, path: EASYITSM_DB_APP_PASSWORD }
                    - { key: owner-username, path: EASYITSM_DB_OWNER_USER }
                    - { key: owner-password, path: EASYITSM_DB_OWNER_PASSWORD }
              {{- with $root.Values.search.opensearch.existingSecret }}
              - secret:
                  name: {{ . }}
                  items:
                    - { key: password, path: EASYITSM_SEARCH_OPENSEARCH_PASSWORD }
              {{- end }}
              {{- with $root.Values.attachments.s3.existingSecret }}
              - secret:
                  name: {{ . }}
                  items:
                    - { key: access-key, path: EASYITSM_ATTACHMENTS_S3_ACCESS_KEY }
                    - { key: secret-key, path: EASYITSM_ATTACHMENTS_S3_SECRET_KEY }
              {{- end }}
              - secret:
                  name: {{ include "easyitsm.appSecretName" $root }}
                  items:
                    - { key: secret-key, path: EASYITSM_IDENTITY_SECRET_KEY }
              {{- with $root.Values.license.existingSecret }}
              - secret:
                  name: {{ . }}
                  items:
                    - { key: license.json, path: license/license.json }
              {{- end }}
              {{- if and $root.Values.audit.siem.enabled $root.Values.audit.siem.caSecret }}
              - secret:
                  name: {{ $root.Values.audit.siem.caSecret }}
                  items:
                    - { key: ca.crt, path: siem/ca.crt }
              {{- end }}
      {{- with $cfg.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $cfg.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $cfg.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}
