#!/bin/sh

set -e

NAMESPACE="$1"
SGCLUSTER="$2"
kubectl get sgcluster -n "$NAMESPACE" "$SGCLUSTER" -o name > /dev/null

READ_ONLY_PODS=
REDUCED_IMPACT="${REDUCED_IMPACT:-true}"

increase_instances_by_one() {
  INSTANCES="$(kubectl get sgcluster -n "$NAMESPACE" "$SGCLUSTER" --template "{{ .spec.instances }}")"
  echo "Inreasing cluster instances from $INSTANCES to $((INSTANCES+1))"
  kubectl patch sgcluster -n "$NAMESPACE" "$SGCLUSTER" --type merge -p "spec: { instances: $((INSTANCES+1)) }"

  wait_read_only_pod "$SGCLUSTER-$INSTANCES"
}

decrease_instances_by_one() {
  INSTANCES="$(kubectl get sgcluster -n "$NAMESPACE" "$SGCLUSTER" --template "{{ .spec.instances }}")"
  echo "Decreasing cluster instances from $INSTANCES to $((INSTANCES-1))"
  kubectl patch sgcluster -n "$NAMESPACE" "$SGCLUSTER" --type merge -p "spec: { instances: $((INSTANCES-1)) }"
}

update_read_only_pods() {
  READ_ONLY_PODS="$([ -z "$READ_ONLY_PODS" ] \
    && kubectl get pod -n "$NAMESPACE" --sort-by '{.metadata.name}' \
      -l "app=StackGresCluster,cluster-name=$SGCLUSTER,cluster=true,role=replica" \
      --template '{{ range .items }}{{ printf "%s\n" .metadata.name }}{{ end }}' \
    || (echo "$READ_ONLY_PODS" | tail -n +2))"
}

delete_read_only_instance() {
  echo "Deleting read-only pod $1"
  kubectl delete -n "$NAMESPACE" pod "$1"
  
  wait_read_only_pod "$1"
}

wait_read_only_pod() {
  echo "Waiting for read only pod $1"
  until kubectl get pod -n "$NAMESPACE" "$1" > /dev/null 2>&1; do sleep 1; done
  kubectl wait --for=condition=Ready -n "$NAMESPACE" "pod/$1"
  while kubectl get -n "$NAMESPACE" pod \
      -l "app=StackGresCluster,cluster-name=$SGCLUSTER,cluster=true,role=replica" -o name \
    | grep -F "pod/$1" | wc -l | grep -q 0; do sleep 1; done
}

delete_primary_instance() {
  echo "Deleting primary pod $1"
  kubectl delete -n "$NAMESPACE" pod "$1"
  
  wait_primary_pod "$1"
}

wait_primary_pod() {
  echo "Waiting for primary pod $1"
  until kubectl get pod -n "$NAMESPACE" "$1" > /dev/null 2>&1; do sleep 1; done
  kubectl wait --for=condition=Ready -n "$NAMESPACE" "pod/$1"
  while kubectl get -n "$NAMESPACE" pod \
      -l "app=StackGresCluster,cluster-name=$SGCLUSTER,cluster=true,role=master" -o name \
    | grep -F "pod/$1" | wc -l | grep -q 0; do sleep 1; done
}

perform_switchover() {
  echo "Performing switchover from primary pod $1 to read only pod $2"
  [ -n "$2" ] && [ -n "$1" ] \
    && kubectl exec -ti -n "$NAMESPACE" "$1" -c patroni -- \
      patronictl switchover --master "$1" --candidate "$2" --force
}

if [ "$REDUCED_IMPACT" = "true" ]
then
  increase_instances_by_one
fi

update_read_only_pods
if [ "$REDUCED_IMPACT" = "true" ]
then
  READ_ONLY_PODS="$(echo "$READ_ONLY_PODS" | head -n -1)"
fi
READ_ONLY_POD="$(echo "$READ_ONLY_PODS" | head -n 1)"
[ -z "$READ_ONLY_POD" ] && echo "No read only pods needs restart" \
  || (
  echo "Read only pods to restart:"
  echo "$READ_ONLY_PODS"
  echo
  echo "$READ_ONLY_POD will be restarted"
  )
while [ -n "$READ_ONLY_POD" ]
do
  delete_read_only_instance "$READ_ONLY_POD"
  update_read_only_pods
  READ_ONLY_POD="$(echo "$READ_ONLY_PODS" | head -n 1)"
  [ -z "$READ_ONLY_POD" ] && echo "No more read only pods needs restart" \
    || echo "$READ_ONLY_POD will be restarted"
done

READ_ONLY_POD="$(kubectl get pod -n "$NAMESPACE" \
    -l "app=StackGresCluster,cluster-name=$SGCLUSTER,cluster=true,role=replica" -o name | head -n 1)"
PRIMARY_POD="$(kubectl get pod -n "$NAMESPACE" \
    -l "app=StackGresCluster,cluster-name=$SGCLUSTER,cluster=true,role=master" -o name | head -n 1)"
READ_ONLY_POD="${READ_ONLY_POD#pod/}"
PRIMARY_POD="${PRIMARY_POD#pod/}"

if [ -n "$READ_ONLY_POD" ]
then
  perform_switchover "$PRIMARY_POD" "$READ_ONLY_POD"
  delete_read_only_instance "$PRIMARY_POD"
else
  echo "Can not perform switchover, no read only pod found"
  delete_primary_instance "$PRIMARY_POD"
fi

if [ "$REDUCED_IMPACT" = "true" ]
then
  decrease_instances_by_one
fi

echo "Cluster restarted sucessfully!"