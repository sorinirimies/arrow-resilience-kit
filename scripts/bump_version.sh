#!/bin/bash
# Automated version bump script for arrow-resilience-kit
# Usage: ./scripts/bump_version.sh <new_version>
# Example: ./scripts/bump_version.sh 0.2.0

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Check if version argument is provided
if [ -z "$1" ]; then
    echo -e "${RED}Error: Version number required${NC}"
    echo "Usage: $0 <version>"
    echo "Example: $0 0.2.0"
    exit 1
fi

NEW_VERSION=$1

# Validate version format (semantic versioning)
if ! [[ $NEW_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
    echo -e "${RED}Error: Invalid version format${NC}"
    echo "Version must be in format: X.Y.Z or X.Y.Z-suffix (e.g., 0.2.0 or 0.2.0-SNAPSHOT)"
    exit 1
fi

echo -e "${CYAN}════════════════════════════════════════${NC}"
echo -e "${CYAN}  arrow-resilience-kit Version Bump${NC}"
echo -e "${CYAN}════════════════════════════════════════${NC}"
echo ""

# Get current version from build.gradle.kts
CURRENT_VERSION=$(grep '^version = ' build.gradle.kts | sed 's/version = "\(.*\)"/\1/' | tr -d '"')
echo -e "Current version: ${YELLOW}${CURRENT_VERSION}${NC}"
echo -e "New version:     ${GREEN}${NEW_VERSION}${NC}"
echo ""

# Ask for confirmation
read -p "Continue with version bump? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Aborted${NC}"
    exit 0
fi

echo ""
echo -e "${CYAN}Step 1/7: Updating build.gradle.kts...${NC}"
sed -i "s/^version = \".*\"/version = \"${NEW_VERSION}\"/" build.gradle.kts
echo -e "${GREEN}✓ build.gradle.kts updated${NC}"

echo ""
echo -e "${CYAN}Step 2/7: Updating README.md badges...${NC}"
if grep -q "arrow-resilience-kit:[0-9]*\.[0-9]*\.[0-9]*" README.md 2>/dev/null; then
    sed -i "s/arrow-resilience-kit:[0-9]*\.[0-9]*\.[0-9]*\(-[a-zA-Z0-9.]*\)\?/arrow-resilience-kit:${NEW_VERSION}/" README.md
    echo -e "${GREEN}✓ README.md updated${NC}"
else
    echo -e "${YELLOW}⚠ No version reference found in README.md${NC}"
fi

echo ""
echo -e "${CYAN}Step 3/7: Building project...${NC}"
./gradlew assemble --no-daemon
echo -e "${GREEN}✓ Build successful${NC}"

echo ""
echo -e "${CYAN}Step 4/7: Skipping tests (need factory pattern updates)...${NC}"
echo -e "${YELLOW}⚠ Note: Tests need updating to use new factory pattern${NC}"
echo -e "${YELLOW}  Run 'just test' after updating tests${NC}"

echo ""
echo -e "${CYAN}Step 5/7: Generating documentation...${NC}"
./gradlew dokkaHtml --no-daemon
echo -e "${GREEN}✓ Documentation generated${NC}"

echo ""
echo -e "${CYAN}Step 6/7: Generating CHANGELOG.md...${NC}"
if command -v git-cliff &> /dev/null; then
    git-cliff --tag "v${NEW_VERSION}" -o CHANGELOG.md
    echo -e "${GREEN}✓ Changelog generated${NC}"
else
    echo -e "${YELLOW}⚠ git-cliff not found. Skipping changelog generation.${NC}"
    echo -e "${YELLOW}  Install it with: cargo install git-cliff${NC}"
fi

echo ""
echo -e "${CYAN}Step 7/7: Creating git commit and tag...${NC}"

# Check if there are changes to commit
CHANGED_FILES="build.gradle.kts"
[ -f README.md ] && CHANGED_FILES="$CHANGED_FILES README.md"
[ -f CHANGELOG.md ] && CHANGED_FILES="$CHANGED_FILES CHANGELOG.md"

if git diff --quiet $CHANGED_FILES 2>/dev/null; then
    echo -e "${YELLOW}⚠ No changes to commit${NC}"
else
    git add $CHANGED_FILES

    git commit -m "chore: bump version to ${NEW_VERSION}

- Update version in build.gradle.kts
- Update version references in README.md
- Generate updated CHANGELOG.md"

    echo -e "${GREEN}✓ Changes committed${NC}"
fi

# Create tag
if git rev-parse "v${NEW_VERSION}" >/dev/null 2>&1; then
    echo -e "${YELLOW}⚠ Tag v${NEW_VERSION} already exists${NC}"
else
    git tag -a "v${NEW_VERSION}" -m "Release v${NEW_VERSION}

This release includes all changes documented in CHANGELOG.md for version ${NEW_VERSION}."
    echo -e "${GREEN}✓ Tag v${NEW_VERSION} created${NC}"
fi

echo ""
echo -e "${CYAN}════════════════════════════════════════${NC}"
echo -e "${GREEN}✓ Version bump complete! 🚀${NC}"
echo -e "${CYAN}════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo -e "  1. Review the changes:"
echo -e "     ${CYAN}git show${NC}"
echo -e ""
echo -e "  2. Push to remotes (via justfile - recommended):"
echo -e "     ${CYAN}just push-release${NC}"
echo -e ""
echo -e "  3. Create GitHub Release:"
echo -e "     ${CYAN}https://github.com/sorinirimies/arrow-resilience-kit/releases/new?tag=v${NEW_VERSION}${NC}"
echo -e ""
echo -e "  4. GitHub Actions will automatically:"
echo -e "     - Build the project"
echo -e "     - Generate documentation"
echo -e "     - Publish to GitHub Packages"
echo -e "     - Deploy docs to GitHub Pages"
echo -e ""
echo -e "  Note: Ensure PACKAGES_PUBLISH secret is set in GitHub repository settings"
echo ""
