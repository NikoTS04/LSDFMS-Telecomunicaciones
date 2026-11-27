PYTHON ?= python3
JAVAC ?= javac
JAVA ?= java
CLASSES := build/classes
SOURCES := $(shell find src -name '*.java')

.PHONY: build generate inspect test demo demo-auto presentation

build:
	mkdir -p $(CLASSES)
	$(JAVAC) --release 17 -encoding UTF-8 -Xlint:all -Werror -d $(CLASSES) $(SOURCES)

generate:
	$(PYTHON) scripts/generate_cdr.py --output data --files 8 --total-size 8MiB

inspect: build
	$(JAVA) -cp $(CLASSES) com.hpc.lsdfms.Main inspect data

test: build
	$(PYTHON) -m unittest discover -s tests -v

demo:
	$(PYTHON) scripts/demo.py

demo-auto:
	$(PYTHON) scripts/demo.py --auto --recovery

presentation:
	$(PYTHON) scripts/presentation_charts.py
