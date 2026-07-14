FROM docker.io/lnbits/lnbits@sha256:6e37fbf9b847c066d7e022e19a018b3df7f12602a370f117857165d36bfb165b

USER root

COPY install_extensions.py /tmp/install_extensions.py
RUN python /tmp/install_extensions.py && rm /tmp/install_extensions.py
