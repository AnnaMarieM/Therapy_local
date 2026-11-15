.PHONY: project build test core-test core-test-docker parse-docker ui-test archive lint format clean

IOS_DIR ?= .
IOS_ABS := $(abspath $(IOS_DIR))
SCHEME ?= MindVault
PROJECT ?= MindVault.xcodeproj
SIMULATOR ?= iPhone 16
SWIFT_IMAGE ?= swift:6.3
CORE_PACKAGE ?= Packages/MindVaultCore

project:
	cd $(IOS_DIR) && xcodegen generate

build: project
	cd $(IOS_DIR) && xcodebuild -project $(PROJECT) -scheme $(SCHEME) -configuration Debug -destination 'generic/platform=iOS Simulator' build

test: project
	cd $(IOS_DIR) && xcodebuild test -project $(PROJECT) -scheme $(SCHEME) -destination 'platform=iOS Simulator,name=$(SIMULATOR)' -resultBundlePath TestResults.xcresult

core-test:
	cd $(IOS_DIR) && swift test --package-path $(CORE_PACKAGE)

core-test-docker:
	docker run --rm -v "$(IOS_ABS):/work" -w /work $(SWIFT_IMAGE) swift test --package-path $(CORE_PACKAGE)

parse-docker:
	docker run --rm -v "$(IOS_ABS):/work" -w /work $(SWIFT_IMAGE) bash -lc 'find Sources Tests -name "*.swift" -print0 | xargs -0 -n1 swiftc -parse'

ui-test: project
	cd $(IOS_DIR) && xcodebuild test -project $(PROJECT) -scheme $(SCHEME) -destination 'platform=iOS Simulator,name=$(SIMULATOR)' -only-testing:MindVaultUITests

archive:
	cd $(IOS_DIR) && xcodebuild archive -project $(PROJECT) -scheme $(SCHEME) -configuration Release -destination 'generic/platform=iOS' -archivePath build/MindVault.xcarchive

lint:
	cd $(IOS_DIR) && swiftlint lint --strict

format:
	cd $(IOS_DIR) && swiftformat .

clean:
	cd $(IOS_DIR) && xcodebuild clean -project $(PROJECT) -scheme $(SCHEME) && rm -rf build TestResults.xcresult
