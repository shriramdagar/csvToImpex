# Setup Guide & Requirements

## Requirements Before Running

Before setting up and running this project, ensure your environment meets the following requirements:

1. **Java Development Kit (JDK)**:
   - **Version**: JDK 21 or higher (JDK 21 or JDK 25 recommended).
   - **Verification**: Run `java -version` and `javac -version` in your terminal or command prompt.
   - **Environment Variable**: Ensure `JAVA_HOME` is set and points to your JDK directory, with the `bin` directory included in your system `PATH`.

2. **Gradle**:
   - No separate Gradle installation is required. The project includes the Gradle Wrapper (`gradlew` / `gradlew.bat`).

---

## Setup Guide by Operating System

### 1. Windows Setup

#### Step 1: Install Java (JDK 21+)
- Download and install JDK 21+ from [Oracle JDK](https://www.oracle.com/java/technologies/downloads/) or [Eclipse Adoptium Temurin](https://adoptium.net/).
- Or install via package manager in PowerShell:
  ```powershell
  winget install EclipseAdoptium.Temurin.21.jdk
  ```
- Verify installation:
  ```powershell
  java -version
  ```
- Set `JAVA_HOME` environment variable (if not set automatically):
  ```powershell
  [Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-21.*", "User")
  ```

#### Step 2: Open Project Directory
- Open Command Prompt (`cmd.exe`) or PowerShell.
- Navigate to the project root folder:
  ```powershell
  cd X:\Paytm
  ```

#### Step 3: Build the Project
- Run the Gradle wrapper to build the JAR:
  ```powershell
  .\gradlew.bat build
  ```

#### Step 4: Run the Application
- **Option A: Launch Desktop UI**
  ```powershell
  .\run.bat
  ```
  *(or via Gradle: `.\gradlew.bat run`)*

- **Option B: Run via CLI**
  ```powershell
  .\run.bat <input-file> [options]
  ```
  *(or directly: `java -jar build\libs\hybris-impex-converter-1.0.0.jar <input-file> [options]`)*

- **Option C: Direct Execution on HAC (UI or CLI)**
  - **In UI**: Click the **"Run on HAC"** button next to Preview. The modal form lets you specify your HAC Import URL, enter credentials (or paste cURL), test connection/CSRF status, and run the script with one click.
  - **In CLI**:
    ```powershell
    .\run.bat <input-file> --run-on-hac --hac-url "https://<host>/hac/console/impex/import" --hac-user admin --hac-pass nimda
    ```

---

### 2. macOS Setup

#### Step 1: Install Java (JDK 21+)
- Install Homebrew (if not already installed), then install JDK 21:
  ```bash
  brew install openjdk@21
  ```
- Symlink the JDK so the system Java wrappers can find it:
  ```bash
  sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
  ```
- Add to your `~/.zshrc` or `~/.bash_profile`:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
  export PATH="$JAVA_HOME/bin:$PATH"
  ```
- Reload shell and verify:
  ```bash
  source ~/.zshrc
  java -version
  ```

#### Step 2: Open Project Directory & Configure Permissions
- Open Terminal.
- Navigate to the project directory:
  ```bash
  cd /path/to/Paytm
  ```
- Grant execution permissions to Gradle wrapper and run scripts:
  ```bash
  chmod +x gradlew run.sh
  ```

#### Step 3: Build the Project
- Run the Gradle wrapper to build the JAR:
  ```bash
  ./gradlew build
  ```

#### Step 4: Run the Application
- **Option A: Launch Desktop UI**
  ```bash
  ./run.sh
  ```
  *(or via Gradle: `./gradlew run`)*

- **Option B: Run via CLI**
  ```bash
  ./run.sh <input-file> [options]
  ```
  *(or directly: `java -jar build/libs/hybris-impex-converter-1.0.0.jar <input-file> [options]`)*

---

### 3. Linux Setup (Ubuntu / Debian / RHEL / Fedora)

#### Step 1: Install Java (JDK 21+)
- **Ubuntu / Debian**:
  ```bash
  sudo apt update
  sudo apt install -y openjdk-21-jdk
  ```
- **Fedora / RHEL / CentOS**:
  ```bash
  sudo dnf install -y java-21-openjdk-devel
  ```
- Set `JAVA_HOME` in `~/.bashrc`:
  ```bash
  echo 'export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))' >> ~/.bashrc
  echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bashrc
  source ~/.bashrc
  ```
- Verify installation:
  ```bash
  java -version
  ```

#### Step 2: Open Project Directory & Configure Permissions
- Open Terminal.
- Navigate to the project directory:
  ```bash
  cd /path/to/Paytm
  ```
- Grant execution permissions:
  ```bash
  chmod +x gradlew run.sh
  ```

#### Step 3: Build the Project
- Run the Gradle wrapper to build the JAR:
  ```bash
  ./gradlew build
  ```

#### Step 4: Run the Application
- **Option A: Launch Desktop UI**
  *(Note: Requires an active X11 or Wayland desktop environment)*
  ```bash
  ./run.sh
  ```
  *(or via Gradle: `./gradlew run`)*

- **Option B: Run via CLI (Headless / Server friendly)**
  ```bash
  ./run.sh <input-file> [options]
  ```
  *(or directly: `java -jar build/libs/hybris-impex-converter-1.0.0.jar <input-file> [options]`)*
