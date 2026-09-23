.PHONY: all build serve demos check clean help

help:
	@./build.sh usage

all:
	./build.sh all $(REF)

build:
	./build.sh build

serve:
	./build.sh serve

demos:
	./build.sh demos $(REF)

check:
	./build.sh check

clean:
	./build.sh clean
