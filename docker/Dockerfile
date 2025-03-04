#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
FROM openjdk:8-jdk as builder
WORKDIR /build
COPY . .
RUN ./gradlew clean build jar dist --parallel --daemon
RUN ./gradlew installPlugin

FROM openjdk:8-jdk
RUN apt-get update && apt-get install -y locales
RUN localedef -i en_US -f UTF-8 en_US.UTF-8 --quiet
WORKDIR /data/app/eventmesh
COPY --from=builder /build/dist ./

EXPOSE 10106 
EXPOSE 10205 10105 10002

ENV DOCKER true
ENV EVENTMESH_HOME /data/app/eventmesh
ENV EVENTMESH_LOG_HOME /data/app/eventmesh/logs
ENV CONFPATH /data/app/eventmesh/conf

CMD ["bash", "bin/start.sh"]
