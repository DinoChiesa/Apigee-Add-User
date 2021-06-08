// Copyright 2021 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.dinochiesa.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.Properties;
import net.oneandone.sushi.util.NetRc;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class AddApigeeUser {
  private final String optString = "-o:P:e:r:d:vn"; // getopt style
  private Hashtable<String, Object> options = new Hashtable<String, Object>();
  private WebDriver driver;
  private Properties props = null;

  public AddApigeeUser(String[] args) throws Exception {
    getOpts(args, optString);
  }

  private void log(String format, Object... args) {
    if (getVerbose()) {
      System.out.printf("[%s] %s\n", nowFormatted(), String.format(format, args));
    }
  }

  private void wait(String label, int millis) throws java.lang.InterruptedException {
    log("waiting [%s] %dms", label, millis);
    Thread.sleep(millis);
  }

  private static int asInteger(String s, int defaultValue) {
    try {
      return Integer.parseInt(s, 10);
    } catch (NumberFormatException exc1) {
      return defaultValue;
    }
  }

  private static String coalesce(String... params) {
    for (String param : params) if (param != null) return param;
    return null;
  }

  public static String nowFormatted() {
    SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss");
    Calendar c = new GregorianCalendar();
    fmt.setCalendar(c);
    return fmt.format(c.getTime());
  }

  private void login(String username, String password) throws java.lang.InterruptedException {
    driver.get("https://login.apigee.com/");
    int loginWait = asInteger((String) props.get("wait.for.login.time"), 24000);

    if (username != null && password != null) {
      log("login username=%s", username);

      int finalWaitTime = 13150;
      try {
        (new WebDriverWait(driver, 10))
          .until(ExpectedConditions.elementToBeClickable(By.xpath("//input[@name='username']")));

        WebElement usernameBox = driver.findElement(By.xpath("//input[@name='username']"));
        usernameBox.sendKeys(username);
        WebElement passwordBox = driver.findElement(By.xpath("//input[@name='password']"));
        passwordBox.sendKeys(password);
        WebElement submitBtn = driver.findElement(By.xpath("//button[@type='submit']"));
        submitBtn.submit();
      } catch (Exception e) {
        // web element does not exist.
        // Maybe wait for SAML login and redirect....
        finalWaitTime = loginWait;
      } finally {
        wait("login", finalWaitTime);  // allow 2FA or SAML signin
        driver.get("https://apigee.com/edge");
      }
    } else {
      if (getVerbose()) {
        System.out.printf("[%s] you should manually login...\n", nowFormatted());
      }
      wait("login", loginWait); // allow 2FA or SAML signin
    }
  }

  private void startChrome(String windowSize) {
    ChromeOptions options = new ChromeOptions();
    // options.addArguments("--start-maximized"); // no worky for me
    // options.addArguments("--window-size=1920,1080");

    if (windowSize == null) {
      windowSize = "2480,1400";
    }
    options.addArguments(String.format("window-size=%s", windowSize));

    if (props.get("chrome.home") != null) {
      String userDataDir = (String) props.get("chrome.home");
      if (getVerbose()) {
        System.out.printf("[%s] startChrome user-data-dir=%s\n", nowFormatted(), userDataDir);
      }
      options.addArguments(String.format("user-data-dir=%s", userDataDir));
    }
    if (props.get("chrome.profile.name") != null) {
      String profileName = (String) props.get("chrome.profile.name");
      if (getVerbose()) {
        System.out.printf("[%s] startChrome profile-directory=%s\n", nowFormatted(), profileName);
      }
      options.addArguments(String.format("profile-directory=%s", profileName));
    }
    driver = new ChromeDriver(options);
  }

  private void actuallyAddOneApigeeUser(String userEmail, String roleDescription)
      throws java.lang.InterruptedException {
    (new WebDriverWait(driver, 10))
        .until(ExpectedConditions.visibilityOfElementLocated(By.id("addBtn")));
    log("click addBtn");
    driver.findElement(By.id("addBtn")).click();
    Thread.sleep(1250); // wait for page load

    // wait for the email field
    (new WebDriverWait(driver, 4))
        .until(ExpectedConditions.visibilityOfElementLocated(By.id("email")));
    log("fill in email");
    WebElement emailBox = driver.findElement(By.id("email"));
    emailBox.sendKeys(userEmail);

    String roleChoicesPath = // "//*[@id='ui-select-choices-0']";
        "//*[@id='userDetails']/fieldset/div[4]/div/div/ul/li/input";

    log("select role");
    WebElement roleChoices = driver.findElement(By.xpath(roleChoicesPath));
    roleChoices.click();

    // find and click the specific LI under that UL
    String desiredChoicePath =
        String.format(
            "//ul[contains(@class,'ui-select-choices')]"
                + "/li[contains(@class,'ui-select-choices-group')]"
                + "/ul[contains(@class,'select2-result-single')]/li/div/div[text()='%s']",
            roleDescription);
    log("find desired role [%s] and click", roleDescription);
    (new WebDriverWait(driver, 10))
        .until(ExpectedConditions.elementToBeClickable(By.xpath(desiredChoicePath)));
    roleChoices.findElement(By.xpath(desiredChoicePath)).click();

    WebElement saveBtn = driver.findElement(By.id("saveBtn"));
    log("save");
    saveBtn.click(); // submit();
  }

  public void visitHomePageAndAddUser(
      final String org, final String userEmail, final String roleDescription) throws Exception {
    try {
      startChrome((String) props.get("windowSize"));

      // driver.get("chrome://version");
      // Thread.sleep(3150);

      login((String) props.get("username"), (String) props.get("password"));
      driver.get(String.format("https://apigee.com/platform/%s/users", org));
      for (String email : userEmail.split(",")) {
        actuallyAddOneApigeeUser(email.trim(), roleDescription);
      }
      Thread.sleep(4150);

    } finally {
      if (driver != null) driver.quit();
    }
  }

  public static void usage() {
    System.out.println("AddApigeeUser: user adder.\n");
    System.out.println(
        "Usage:\n  java com.dinochiesa.tools.AddApigeeUser [-v] [-P <propsfile>] [-n]");
  }

  private static NetRc.Authenticator getAuthenticator(String host) throws Exception {
    NetRc netrc = new NetRc();
    String netrcPath = String.format("%s/.netrc", System.getProperty("user.home"));
    InputStream in = new FileInputStream(netrcPath);
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    netrc.parse(reader);
    return netrc.getAuthenticator(host);
  }

  private static Properties loadProps(String filename) throws IOException {
    Properties prop = new Properties();
    InputStream input = null;
    try {
      input = new FileInputStream(filename);
      prop.load(input);
      return prop;
    } finally {
      if (input != null) {
        input.close();
      }
    }
  }

  private void getOpts(String[] args, String optString) throws Exception {
    // Parse command line args for args in the following format:
    //   -a value -b value2 ... ...

    // sanity checks
    if (args == null) return;
    if (args.length == 0) return;
    if (optString == null) return;
    final String argPrefix = "-";
    String patternString = "^" + argPrefix + "([" + optString.replaceAll(":", "") + "])";

    java.util.regex.Pattern p = java.util.regex.Pattern.compile(patternString);

    int L = args.length;
    for (int i = 0; i < L; i++) {
      String arg = args[i];
      java.util.regex.Matcher m = p.matcher(arg);
      if (!m.matches()) {
        throw new java.lang.Exception(
            "The command line arguments are improperly formed. Use a form like '-a value' or just '-b' .");
      }

      char ch = arg.charAt(1);
      int pos = optString.indexOf(ch);

      if ((pos != optString.length() - 1) && (optString.charAt(pos + 1) == ':')) {
        if (i + 1 < L) {
          i++;
          Object current = this.options.get(m.group(1));
          if (current == null) {
            // not a previously-seen option
            this.options.put(m.group(1), args[i]);
          } else if (current instanceof ArrayList<?>) {
            // previously seen, and already a list
            @SuppressWarnings("unchecked")
            ArrayList<String> oldList = (ArrayList<String>) current;
            oldList.add(args[i]);
          } else {
            // we have one value, need to make a list
            ArrayList<String> newList = new ArrayList<String>();
            newList.add((String) current);
            newList.add(args[i]);
            this.options.put(m.group(1), newList);
          }
        } else {
          throw new java.lang.Exception("Incorrect arguments.");
        }
      } else {
        // a "no-value" argument, like -v for verbose
        options.put(m.group(1), (Boolean) true);
      }
    }
  }

  private boolean getVerbose() {
    Boolean v = (Boolean) this.options.get("v");
    return (v != null && v);
  }

  public void run() throws Exception {

    if (getVerbose()) {
      Path currentRelativePath = Paths.get("");
      String s = currentRelativePath.toAbsolutePath().toString();
      System.out.printf("[%s] Current path is: %s\n", nowFormatted(), s);
    }

    String propsFile = (String) this.options.get("P");
    if (propsFile == null) {
      // default
      File f = new File("./addapigeeuser.properties");
      if (f.exists() && !f.isDirectory()) {
        props = loadProps("./addapigeeuser.properties");
      } else {
        throw new IllegalStateException(
            "missing P argument and cannot find adduser.properties file");
      }
    } else {
      props = loadProps(propsFile);
    }

    Boolean useNetrc = (Boolean) this.options.get("n");
    useNetrc = (useNetrc != null && useNetrc);

    if (useNetrc) {
      NetRc.Authenticator auth = getAuthenticator("login.apigee.com");
      if (auth == null) {
        throw new IllegalStateException("cannot find apigee.com in .netrc");
      }
      props.setProperty("username", auth.getUser());
      props.setProperty("password", auth.getPass());
    } else {
      String username = (String) props.get("username");
      String password = (String) props.get("password");
      if (username != null && password != null) {
        props.setProperty("username", username);
        props.setProperty("password", password);
      }
    }

    String chromeDriverPropName = "webdriver.chrome.driver";
    String chromeDriverPath =
        coalesce((String) this.options.get("d"), (String) props.get(chromeDriverPropName));
    if (chromeDriverPath != null) System.setProperty(chromeDriverPropName, chromeDriverPath);

    String org = coalesce((String) this.options.get("o"), (String) props.get("org"));
    if (org == null || org.equals("")) throw new IllegalStateException("missing org");
    String userEmail = coalesce((String) this.options.get("e"), (String) props.get("email"));
    if (userEmail == null || userEmail.equals(""))
      throw new IllegalStateException("missing userEmail property");
    String roleDescription = coalesce((String) this.options.get("r"), (String) props.get("role"));
    if (roleDescription == null || roleDescription.equals(""))
      throw new IllegalStateException("missing role property");

    visitHomePageAndAddUser(org, userEmail, roleDescription);
  }

  public static void main(String[] args) {
    try {
      AddApigeeUser me = new AddApigeeUser(args);
      me.run();
    } catch (java.lang.Exception exc1) {
      System.out.println("Exception:" + exc1.toString());
      exc1.printStackTrace();
      usage();
    }
  }
}
