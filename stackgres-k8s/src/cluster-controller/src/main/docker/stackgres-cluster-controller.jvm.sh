#!/bin/sh

JAVA_OPTS="${JAVA_OPTS:-"-Djava.net.preferIPv4Stack=true -Djava.awt.headless=true -XX:MaxRAMPercentage=75.0"}"
APP_OPTS="${APP_OPTS:-"-Dquarkus.http.host=0.0.0.0 -Dquarkus.http.port=8080 -Dquarkus.http.ssl-port=8443 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"}"
if [ "$DEBUG_DISTRIBUTEDLOGS_CONTROLLER" = true ]
then
  set -x
  JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=$([ "$DEBUG_DISTRIBUTEDLOGS_CONTROLLER_SUSPEND" = true ] && echo y || echo n)"
fi
if [ -n "$DISTRIBUTEDLOGS_CONTROLLER_LOG_LEVEL" ]
then
  APP_OPTS="$APP_OPTS -Dquarkus.log.level=$DISTRIBUTEDLOGS_CONTROLLER_LOG_LEVEL"
fi
if [ "$DISTRIBUTEDLOGS_CONTROLLER_SHOW_STACK_TRACES" = true ]
then
  APP_OPTS="$APP_OPTS -Dquarkus.log.console.format=%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c{4.}] (%t) %s%e%n"
fi
exec java $JAVA_OPTS -jar /app/stackgres-cluster-controller.jar $APP_OPTS
