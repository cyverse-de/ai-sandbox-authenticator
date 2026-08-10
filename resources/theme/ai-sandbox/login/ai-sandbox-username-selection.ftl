<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.exists('username') displayInfo=false; section>
    <#if section = "header">
        Choose a Username
    <#elseif section = "form">
        <form id="kc-username-selection-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">

            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="username" class="${properties.kcLabelClass!}">
                        Username
                    </label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <input type="text" id="username" name="username" class="${properties.kcInputClass!}"
                           value="${attemptedUsername!''}"
                           autofocus autocomplete="off"
                            aria-invalid="${messagesPerField.exists('username')?string('true','false')}"
                           pattern="[0-9a-z]+"
                           title="Username must contain only lowercase letters and numbers" />
                </div>
                <#if messagesPerField.exists('username')>
                    <span id="input-error-username" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('username'))?no_esc}
                    </span>
                </#if>
            </div>

            <#if !messagesPerField.exists('username')>
                <p class="${properties.kcFormGroupClass!}">
                    Please choose a username containing only lowercase letters and numbers.
                </p>
            </#if>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                           type="submit" value="Continue" />
                </div>
            </div>

            <input type="hidden" name="brokerContext" value="${brokerContext!''}" />
        </form>
    </#if>
</@layout.registrationLayout>
