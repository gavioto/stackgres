set -e

to_json_string() {
  sed ':a;N;$!ba;s/\n/\\n/g' | sed 's/\(["\\\t]\)/\\\1/g' | tr '\t' 't'
}

try_lock() {
  local WAIT="$1"
  local TEMPLATE='
  LOCK_POD={{ if .metadata.annotations.lockPod }}{{ .metadata.annotations.lockPod }}{{ else }}{{ end }}
  LOCK_TIMESTAMP={{ if .metadata.annotations.lockTimestamp }}{{ .metadata.annotations.lockTimestamp }}{{ else }}0{{ end }}
  RESOURCE_VERSION={{ .metadata.resourceVersion }}
  '
  kubectl get cronjob.batch -n "$CLUSTER_NAMESPACE" "$CRONJOB_NAME" --template "$TEMPLATE" > /tmp/current-backup-job
  . /tmp/current-backup-job
  CURRENT_TIMESTAMP="$(date +%s)"
  if [ "$POD_NAME" != "$LOCK_POD" ] && [ "$((CURRENT_TIMESTAMP-LOCK_TIMESTAMP))" -lt 15 ]
  then
    echo "Locked already by $LOCK_POD at $(date -d @"$LOCK_TIMESTAMP" --iso-8601=seconds --utc)"
    if "$WAIT"
    then
      sleep 20
      try_lock true
    else
      return 1
    fi
  fi
  if ! kubectl annotate cronjob.batch -n "$CLUSTER_NAMESPACE" "$CRONJOB_NAME" \
    --resource-version "$RESOURCE_VERSION" --overwrite "lockPod=$POD_NAME" "lockTimestamp=$CURRENT_TIMESTAMP"
  then
    kubectl get cronjob.batch -n "$CLUSTER_NAMESPACE" "$CRONJOB_NAME" --template "$TEMPLATE" > /tmp/current-backup-job
    . /tmp/current-backup-job
    if [ "$POD_NAME" = "$LOCK_POD" ]
    then
      try_lock "$WAIT"
      return 0
    fi
    echo "Locked by $LOCK_POD at $(date -d @"$LOCK_TIMESTAMP" --iso-8601=seconds --utc)"
    if "$WAIT"
    then
      sleep 20
      try_lock true
    else
      return 1
    fi
  fi
}

try_lock true > /tmp/try-lock
echo "Lock acquired"
(
while true
do
  sleep 5
  try_lock false > /tmp/try-lock
done
) &
try_lock_pid=$!

backup_cr_template="{{ range .items }}"
backup_cr_template="${backup_cr_template}{{ .spec.sgCluster }}"
backup_cr_template="${backup_cr_template}:{{ .metadata.name }}"
backup_cr_template="${backup_cr_template}:{{ with .status.process.status }}{{ . }}{{ end }}"
backup_cr_template="${backup_cr_template}:{{ with .status.internalName }}{{ . }}{{ end }}"
backup_cr_template="${backup_cr_template}:{{ with .status.process.jobPod }}{{ . }}{{ end }}"
backup_cr_template="${backup_cr_template}:{{ with .metadata.labels }}{{ with index . \"$SCHEDULED_BACKUP_KEY\" }}{{ . }}{{ end }}{{ end }}"
backup_cr_template="${backup_cr_template}:{{ if .spec.managedLifecycle }}true{{ else }}false{{ end }}"
backup_cr_template="${backup_cr_template}:{{ if .status.process.managedLifecycle }}true{{ else }}false{{ end }}"
backup_cr_template="${backup_cr_template}{{ printf "'"\n"'" }}{{ end }}"
kubectl get "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" \
  --template "$backup_cr_template" > /tmp/all-backups
grep "^$CLUSTER_NAME:" /tmp/all-backups > /tmp/backups || true

if [ -n "$SCHEDULED_BACKUP_KEY" ]
then
  BACKUP_NAME="${CLUSTER_NAME}-$(date +%Y-%m-%d-%H-%M-%S)"
fi

BACKUP_CONFIG_RESOURCE_VERSION="$(kubectl get "$BACKUP_CONFIG_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_CONFIG" --template '{{ .metadata.resourceVersion }}')"
BACKUP_CONFIG_YAML=$(cat << BACKUP_CONFIG_YAML
    baseBackups:
      compression: "{{ .spec.baseBackups.compression }}"
    storage:
      type: "{{ .spec.storage.type }}"
      {{- with .spec.storage.s3 }}
      s3:
        bucket: "{{ .bucket }}"
        {{ with .path }}path: "{{ . }}"{{ end }}
        awsCredentials:
          secretKeySelectors:
            accessKeyId:
              key: "{{ .awsCredentials.secretKeySelectors.accessKeyId.key }}"
              name: "{{ .awsCredentials.secretKeySelectors.accessKeyId.name }}"
            secretAccessKey:
              key: "{{ .awsCredentials.secretKeySelectors.secretAccessKey.key }}"
              name: "{{ .awsCredentials.secretKeySelectors.secretAccessKey.name }}"
        {{ with .region }}region: "{{ . }}"{{ end }}
        {{ with .storageClass }}storageClass: "{{ . }}"{{ end }}
      {{- end }}
      {{- with .spec.storage.s3Compatible }}
      s3Compatible:
        bucket: "{{ .bucket }}"
        {{ with .path }}path: "{{ . }}"{{ end }}
        awsCredentials:
          secretKeySelectors:
            accessKeyId:
              key: "{{ .awsCredentials.secretKeySelectors.accessKeyId.key }}"
              name: "{{ .awsCredentials.secretKeySelectors.accessKeyId.name }}"
            secretAccessKey:
              key: "{{ .awsCredentials.secretKeySelectors.secretAccessKey.key }}"
              name: "{{ .awsCredentials.secretKeySelectors.secretAccessKey.name }}"
        {{ with .region }}region: "{{ . }}"{{ end }}
        {{ with .endpoint }}endpoint: "{{ . }}"{{ end }}
        {{ with .enablePathStyleAddressing }}enablePathStyleAddressing: {{ . }}{{ end }}
        {{ with .storageClass }}storageClass: "{{ . }}"{{ end }}
      {{- end }}
      {{- with .spec.storage.gcs }}
      gcs:
        bucket: "{{ .bucket }}"
        {{ with .path }}path: "{{ . }}"{{ end }}
        gcpCredentials:
          {{- if .gcpCredentials.fetchCredentialsFromMetadataService }}
          fetchCredentialsFromMetadataService: true
          {{- else }}
          secretKeySelectors:
            serviceAccountJSON:
              key: "{{ .gcpCredentials.secretKeySelectors.serviceAccountJSON.key }}"
              name: "{{ .gcpCredentials.secretKeySelectors.serviceAccountJSON.name }}"
          {{- end }}
      {{- end }}
      {{- with .spec.storage.azureBlob }}
      azureBlob:
        bucket: "{{ .bucket }}"
        {{ with .path }}path: "{{ . }}"{{ end }}
        azureCredentials:
          secretKeySelectors:
            storageAccount:
              key: "{{ .azureCredentials.secretKeySelectors.storageAccount.key }}"
              name: "{{ .azureCredentials.secretKeySelectors.storageAccount.name }}"
            accessKey:
              key: "{{ .azureCredentials.secretKeySelectors.accessKey.key }}"
              name: "{{ .azureCredentials.secretKeySelectors.accessKey.name }}"
      {{- end }}
BACKUP_CONFIG_YAML
)
BACKUP_STATUS_YAML=$(cat << BACKUP_STATUS_YAML
status:
  process:
    status: "$BACKUP_PHASE_RUNNING"
    jobPod: "$POD_NAME"
  sgBackupConfig:
$(kubectl get "$BACKUP_CONFIG_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_CONFIG" --template "$BACKUP_CONFIG_YAML")
BACKUP_STATUS_YAML
)

if ! kubectl get "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" -o name >/dev/null 2>&1
then
  echo "Creating backup CR"
  cat << EOF | kubectl create -f - -o yaml
apiVersion: $BACKUP_CRD_APIVERSION
kind: $BACKUP_CRD_KIND
metadata:
  namespace: "$CLUSTER_NAMESPACE"
  name: "$BACKUP_NAME"
  annotations:
    $SCHEDULED_BACKUP_KEY: "$RIGHT_VALUE"
spec:
  sgCluster: "$CLUSTER_NAME"
  managedLifecycle: true
$BACKUP_STATUS_YAML
EOF
else
  if ! kubectl get "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" --template "{{ .status.process.status }}" \
    | grep -q "^$BACKUP_PHASE_COMPLETED$"
  then
    echo "Updating backup CR"
    kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" -o yaml --type merge --patch "$(
      (
        kubectl get "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" -o yaml
        echo "$BACKUP_STATUS_YAML"
      ) | kubectl create --dry-run=client -f - -o json)"
  else
    echo "Already completed backup. Nothing to do!"
    exit
  fi
fi

current_backup_config="$(kubectl get "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" \
  --template "{{ .status.sgBackupConfig.storage }}")"

(
echo "Retrieving primary and replica"
kubectl get pod -n "$CLUSTER_NAMESPACE" -l "${PATRONI_CLUSTER_LABELS},${PATRONI_ROLE_KEY}=${PATRONI_PRIMARY_ROLE}" -o name > /tmp/current-primary
kubectl get pod -n "$CLUSTER_NAMESPACE" -l "${PATRONI_CLUSTER_LABELS},${PATRONI_ROLE_KEY}=${PATRONI_REPLICA_ROLE}" -o name | head -n 1 > /tmp/current-replica-or-primary
if [ ! -s /tmp/current-primary ]
then
  kubectl get pod -n "$CLUSTER_NAMESPACE" -l "${PATRONI_CLUSTER_LABELS}" >&2
  echo > /tmp/backup-push
  echo "Unable to find primary, backup aborted" >> /tmp/backup-push
  [ -n "$SCHEDULED_BACKUP_KEY" ] || sleep 20
  exit 1
fi
if [ ! -s /tmp/current-replica-or-primary ]
then
  cat /tmp/current-primary > /tmp/current-replica-or-primary
  echo "Primary is $(cat /tmp/current-primary)"
  echo "Replica not found, primary will be used for cleanups"
else
  echo "Primary is $(cat /tmp/current-primary)"
  echo "Replica is $(cat /tmp/current-replica-or-primary)"
fi
echo "Performing backup"
cat << EOF | kubectl exec -i -n "$CLUSTER_NAMESPACE" "$(cat /tmp/current-primary)" -c patroni \
  -- sh -e $(! echo $- | grep -q x || echo " -x") > /tmp/backup-push 2>&1
exec-with-env "$BACKUP_ENV" \\
  -- wal-g backup-push "$PG_DATA_PATH" -f $([ "$BACKUP_IS_PERMANENT" = true ] && echo '-p' || true)
EOF
if grep -q " Wrote backup with name " /tmp/backup-push
then
  WAL_G_BACKUP_NAME="$(grep " Wrote backup with name " /tmp/backup-push | sed 's/.* \([^ ]\+\)$/\1/')"
fi
echo "Backup completed"
set +e
echo "Cleaning up old backups"
cat << EOF | kubectl exec -i -n "$CLUSTER_NAMESPACE" "$(cat /tmp/current-replica-or-primary)" -c patroni \
  -- sh -e $(! echo $- | grep -q x || echo " -x")
# for each existing backup sorted by backup name ascending (this also mean sorted by creation date ascending)
exec-with-env "$BACKUP_ENV" \\
  -- wal-g backup-list --detail --json \\
  | tr -d '[]' | sed 's/},{/}|{/g' | tr '|' '\\n' \\
  | grep '"backup_name"' \\
  | sort -r -t , -k 2 \\
  | (RETAIN="$RETAIN"
    while read backup
    do
      backup_name="\$(echo "\$backup" | tr -d '{}\\42' | tr ',' '\\n' \\
          | grep 'backup_name' | cut -d : -f 2-)"
      # if is not the created backup and is not in backup CR list, mark as impermanent
      if [ "\$backup_name" != "$WAL_G_BACKUP_NAME" ] \\
        && ! echo '$(cat /tmp/backups)' \\
        | cut -d : -f 4 \\
        | grep -v '^\$' \\
        | grep -q "^\$backup_name\$"
      then
        if echo "\$backup" | grep -q "\\"is_permanent\\":true"
        then
          exec-with-env "$BACKUP_ENV" \\
            -- wal-g backup-mark -i "\$backup_name"
        fi
      # if is inside the retain window, mark as permanent and decrease RETAIN counter
      elif [ "\$RETAIN" -gt 0 ]
      then
        if [ "\$backup_name" = "$WAL_G_BACKUP_NAME" -a "$BACKUP_IS_PERMANENT" != true ] \\
          || echo "\$backup" | grep -q "\\"is_permanent\\":false"
        then
          exec-with-env "$BACKUP_ENV" \\
            -- wal-g backup-mark "\$backup_name"
        fi
        RETAIN="\$((RETAIN-1))"
      # if is outside the retain window...
      elif [ "\$RETAIN" -le 0 ]
      then
        # ... and has a managed lifecycle, mark as impermanent
        if echo '$(cat /tmp/backups)' \\
          | grep '^[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:true' \\
          | cut -d : -f 4 \\
          | grep -v '^\$' \\
          | grep -q "^\$backup_name\$" \\
          && echo "\$backup" | grep -q "\\"is_permanent\\":true"
        then
          exec-with-env "$BACKUP_ENV" \\
            -- wal-g backup-mark -i "\$backup_name"
        # ... and has not a managed lifecycle, mark as permanent
        elif echo '$(cat /tmp/backups)' \\
          | grep -v '^[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:true' \\
          | cut -d : -f 4 \\
          | grep -v '^\$' \\
          | grep -q "^\$backup_name\$" \\
          && echo "\$backup" | grep -q "\\"is_permanent\\":false"
        then
          exec-with-env "$BACKUP_ENV" \\
            -- wal-g backup-mark "\$backup_name"
        fi
      fi
    done)

# removes all backups that are marked as impermanent
exec-with-env "$BACKUP_ENV" \\
  -- wal-g delete retain FIND_FULL "0" --confirm

# for each existing backup
exec-with-env "$BACKUP_ENV" \\
  -- wal-g backup-list --detail --json \\
  | tr -d '[]' | sed 's/},{/}|{/g' | tr '|' '\\n' \\
  | grep '"backup_name"' \\
  | while read backup
    do
      backup_name="\$(echo "\$backup" | tr -d '{}\\42' | tr ',' '\\n' \\
          | grep 'backup_name' | cut -d : -f 2-)"
      # if is the created backup and has a managed lifecycle, mark as impermanent
      if [ "\$backup_name" = "$WAL_G_BACKUP_NAME" -a "$BACKUP_IS_PERMANENT" != true ] \\
        || (echo '$(cat /tmp/backups)' \\
        | grep '^[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:true' \\
        | cut -d : -f 4 \\
        | grep -v '^\$' \\
        | grep -q "^\$backup_name\$" \\
        && echo "\$backup" | grep -q "\\"is_permanent\\":true")
      then
        exec-with-env "$BACKUP_ENV" \\
          -- wal-g backup-mark -i "\$backup_name"
      # if has not a managed lifecycle, mark as permanent
      elif echo '$(cat /tmp/backups)' \\
        | grep -v '^[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:true' \\
        | cut -d : -f 4 \\
        | grep -v '^\$' \\
        | grep -q "^\$backup_name\$" \\
        && echo "\$backup" | grep -q "\\"is_permanent\\":false"
      then
        exec-with-env "$BACKUP_ENV" \\
          -- wal-g backup-mark "\$backup_name"
      fi
    done
EOF
if [ "$?" = 0 ]
then
  echo "Cleanup completed"
else
  echo "Cleanup failed"
fi
) &
pid=$!

set +e
wait -n "$pid" "$try_lock_pid"
RESULT=$?
if kill -0 "$pid" 2>/dev/null
then
  kill "$pid"
  kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" --type json --patch '[
    {"op":"replace","path":"/status/process/status","value":"'"$BACKUP_PHASE_FAILED"'"},
    {"op":"replace","path":"/status/process/failure","value":"Lock lost:\n'"$(cat /tmp/try-lock | to_json_string)"'"}
    ]'
  cat /tmp/try-lock
  echo "Lock lost"
  exit 1
else
  kill "$try_lock_pid"
  if [ "$RESULT" != 0 ]
  then
    kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" --type json --patch '[
      {"op":"replace","path":"/status/process/status","value":"'"$BACKUP_PHASE_FAILED"'"},
      {"op":"replace","path":"/status/process/failure","value":"Backup failed: '"$(cat /tmp/backup-push | to_json_string)"'"}
      ]'
    cat /tmp/backup-push
    echo "Backup failed"
    [ -n "$SCHEDULED_BACKUP_KEY" ] || sleep 20
    exit 1
  fi
fi

if grep -q " Wrote backup with name " /tmp/backup-push
then
  WAL_G_BACKUP_NAME="$(grep " Wrote backup with name " /tmp/backup-push | sed 's/.* \([^ ]\+\)$/\1/')"
fi

if [ ! -z "$WAL_G_BACKUP_NAME" ]
then
  set +x
  cat << EOF | kubectl exec -i -n "$CLUSTER_NAMESPACE" "$(cat /tmp/current-replica-or-primary)" -c patroni \
    -- sh -e > /tmp/backup-list 2>&1
WALG_LOG_LEVEL= exec-with-env "$BACKUP_ENV" \\
  -- wal-g backup-list --detail --json
EOF
  RESULT=$?
  set -x
  if [ "$RESULT" != 0 ]
  then
    kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" --type json --patch '[
      {"op":"replace","path":"/status/process/status","value":"'"$BACKUP_PHASE_FAILED"'"},
      {"op":"replace","path":"/status/process/failure","value":"Backup can not be listed after creation '"$(cat /tmp/backup-list | to_json_string)"'"}
      ]'
    cat /tmp/backup-list
    echo "Backups can not be listed after creation"
    [ -n "$SCHEDULED_BACKUP_KEY" ] || sleep 20
    exit 1
  fi
  cat /tmp/backup-list | tr -d '[]' | sed 's/},{/}|{/g' | tr '|' '\n' \
    | grep '"backup_name":"'"$WAL_G_BACKUP_NAME"'"' | tr -d '{}"' | tr ',' '\n' > /tmp/current-backup
  if [ "$BACKUP_CONFIG_RESOURCE_VERSION" != "$(kubectl get "$BACKUP_CONFIG_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_CONFIG" --template '{{ .metadata.resourceVersion }}')" ]
  then
    kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" --type json --patch '[
      {"op":"replace","path":"/status/process/status","value":"'"$BACKUP_PHASE_FAILED"'"},
      {"op":"replace","path":"/status/process/failure","value":"Backup configuration '"$BACKUP_CONFIG"' changed during backup"}
      ]'
    cat /tmp/backup-list
    echo "Backup configuration '$BACKUP_CONFIG' changed during backup"
    [ -n "$SCHEDULED_BACKUP_KEY" ] || sleep 20
    exit 1
  elif ! grep -q "^backup_name:${WAL_G_BACKUP_NAME}$" /tmp/current-backup
  then
    kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" --type json --patch '[
      {"op":"replace","path":"/status/process/status","value":"'"$BACKUP_PHASE_FAILED"'"},
      {"op":"replace","path":"/status/process/failure","value":"Backup '"$WAL_G_BACKUP_NAME"' was not found after creation"}
      ]'
    cat /tmp/backup-list
    echo "Backup '$WAL_G_BACKUP_NAME' was not found after creation"
    [ -n "$SCHEDULED_BACKUP_KEY" ] || sleep 20
    exit 1
  else
    existing_backup_is_permanent="$(grep "^is_permanent:" /tmp/current-backup | cut -d : -f 2-)"
    is_backup_subject_to_retention_policy=""
    if [ "$existing_backup_is_permanent" = "true" ]
    then
      is_backup_subject_to_retention_policy="false"
    else
      is_backup_subject_to_retention_policy="true"
    fi
    kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" --type json --patch '[
      {"op":"replace","path":"/status/internalName","value":"'"$WAL_G_BACKUP_NAME"'"},
      {"op":"replace","path":"/status/process/status","value":"'"$BACKUP_PHASE_COMPLETED"'"},
      {"op":"replace","path":"/status/process/failure","value":""},
      {"op":"replace","path":"/status/process/managedLifecycle","value":'$is_backup_subject_to_retention_policy'},
      {"op":"replace","path":"/status/process/timing","value":{
          "stored":"'"$(grep "^time:" /tmp/current-backup | cut -d : -f 2-)"'",
          "start":"'"$(grep "^start_time:" /tmp/current-backup | cut -d : -f 2-)"'",
          "end":"'"$(grep "^finish_time:" /tmp/current-backup | cut -d : -f 2-)"'"
        }
      },
      {"op":"replace","path":"/status/backupInformation","value":{
          "startWalFile":"'"$(grep "^wal_file_name:" /tmp/current-backup | cut -d : -f 2-)"'",
          "timeline":"'"$(grep "^wal_file_name:" /tmp/current-backup | cut -d : -f 2- | awk '{startWal=substr($0, 0, 9); timeline=startWal+0; print timeline}')"'",
          "hostname":"'"$(grep "^hostname:" /tmp/current-backup | cut -d : -f 2-)"'",
          "sourcePod":"'"$(grep "^hostname:" /tmp/current-backup | cut -d : -f 2-)"'",
          "pgData":"'"$(grep "^data_dir:" /tmp/current-backup | cut -d : -f 2-)"'",
          "postgresVersion":"'"$(grep "^pg_version:" /tmp/current-backup | cut -d : -f 2-)"'",
          "systemIdentifier":"'"$(grep "^system_identifier:" /tmp/current-backup | cut -d : -f 2-)"'",
          "lsn":{
            "start":"'"$(grep "^start_lsn:" /tmp/current-backup | cut -d : -f 2-)"'",
            "end":"'"$(grep "^finish_lsn:" /tmp/current-backup | cut -d : -f 2-)"'"
          },
          "size":{
            "uncompressed":'"$(grep "^uncompressed_size:" /tmp/current-backup | cut -d : -f 2-)"',
            "compressed":'"$(grep "^compressed_size:" /tmp/current-backup | cut -d : -f 2-)"'
          }
        }
      }
    ]'

  fi
  echo "Reconcile backup CRs"
  cat /tmp/backup-list | tr -d '[]' | sed 's/},{/}|{/g' | tr '|' '\n' \
    | grep '"backup_name"' \
    > /tmp/existing-backups
  kubectl get pod -n "$CLUSTER_NAMESPACE" \
    --template "{{ range .items }}{{ .metadata.name }}{{ printf "'"\n"'" }}{{ end }}" \
    > /tmp/pods
  for backup in $(cat /tmp/backups)
  do
    backup_cr_name="$(echo "$backup" | cut -d : -f 2)"
    backup_phase="$(echo "$backup" | cut -d : -f 3)"
    backup_name="$(echo "$backup" | cut -d : -f 4)"
    backup_pod="$(echo "$backup" | cut -d : -f 5)"
    backup_sheduled_backup="$(echo "$backup" | cut -d : -f 6)"
    backup_managed_lifecycle="$(echo "$backup" | cut -d : -f 8)"
    backup_is_permanent="$([ "$backup_managed_lifecycle" = true ] && echo false || echo true)"
    backup_config="$(kubectl get "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$backup_cr_name" \
      --template "{{ .status.sgBackupConfig.storage }}")"
    # if backup CR has backup internal name, is marked as completed, uses the same current
    # backup config but is not found in the storage, delete it
    if [ -n "$backup_name" ] && [ "$backup_phase" = "$BACKUP_PHASE_COMPLETED" ] \
      && [ "$backup_config" = "$current_backup_config" ] \
      && ! grep -q "\"backup_name\":\"$backup_name\"" /tmp/existing-backups
    then
      kubectl delete "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$backup_cr_name"
    # if backup CR is a scheduled backup, is marked as running, has no pod or pod
    # has been terminated, delete it
    elif [ "$backup_sheduled_backup" = "$RIGHT_VALUE" ] \
      && [ "$backup_phase" = "$BACKUP_PHASE_RUNNING" ] \
      && ([ -z "$backup_pod" ] || ! grep -q "^$backup_pod$" /tmp/pods)
    then
      kubectl delete "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$backup_cr_name"
    # if backup CR has backup internal name, is marked as completed, and is marked as
    # stored as not managed lifecycle or managed lifecycle and is found as managed lifecycle or
    # not managed lifecycle respectively, then mark it as stored as managed lifecycle or
    # not managed lifecycle respectively
    elif [ -n "$backup_name" ] && [ "$backup_phase" = "$BACKUP_PHASE_COMPLETED" ] \
      && ! grep "\"backup_name\":\"$backup_name\"" /tmp/existing-backups \
        | grep -q "\"is_permanent\":$backup_is_permanent"
    then
      existing_backup_is_permanent="$(grep "\"backup_name\":\"$backup_name\"" /tmp/existing-backups \
        | tr -d '{}"' | tr ',' '\n' | grep "^is_permanent:" | cut -d : -f 2-)"
      is_backup_subject_to_retention_policy=""
      if [ "$existing_backup_is_permanent" = "true" ]
      then
        is_backup_subject_to_retention_policy="false"
      else
        is_backup_subject_to_retention_policy="true"
      fi
      kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$backup_cr_name" --type json --patch '[
        {"op":"replace","path":"/status/process/managedLifecycle","value":'$is_backup_subject_to_retention_policy'}
        ]'
    fi
  done
else
  kubectl patch "$BACKUP_CRD_NAME" -n "$CLUSTER_NAMESPACE" "$BACKUP_NAME" --type json --patch '[
    {"op":"replace","path":"/status/process/status","value":"'"$BACKUP_PHASE_FAILED"'"},
    {"op":"replace","path":"/status/process/failure","value":"Backup name not found in backup-push log:\n'"$(cat /tmp/backup-push | to_json_string)"'"}
    ]'
  cat /tmp/backup-push
  echo "Backup name not found in backup-push log"
  [ -n "$SCHEDULED_BACKUP_KEY" ] || sleep 20
  exit 1
fi
