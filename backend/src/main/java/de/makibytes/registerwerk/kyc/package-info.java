@org.springframework.modulith.ApplicationModule(
        displayName = "KYC",
        allowedDependencies = {"shared", "audit", "customer", "screening"}
)
package de.makibytes.registerwerk.kyc;
