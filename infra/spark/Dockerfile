ARG SPARK_IMAGE
FROM ${SPARK_IMAGE}

USER root
COPY sparkjobs/requirements.txt /opt/ddubi/requirements.txt
RUN pip install --no-cache-dir -r /opt/ddubi/requirements.txt

WORKDIR /opt/ddubi/sparkjobs
