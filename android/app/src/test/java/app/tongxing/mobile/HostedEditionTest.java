package app.tongxing.mobile;

import android.app.Application;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class HostedEditionTest {
    @Test public void loginAndRegistrationHaveNoAddressInputAndIgnoreAlternateHosts() throws Exception {
        assumeTrue(Api.fixedServer());
        assertEquals("https://tongxing.byterecall.xyz", Api.loginBase(""));
        assertEquals("https://tongxing.byterecall.xyz", Api.loginBase("https://example.org"));
        Api.get(RuntimeEnvironment.getApplication()).clearSession();
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            ViewGroup content = controller.get().findViewById(android.R.id.content);
            assertEquals(2, content.getTouchables().stream().filter(EditText.class::isInstance).count());
            TextView register = (TextView) content.getTouchables().stream()
                    .filter(view -> view instanceof TextView text && "创建账号".contentEquals(text.getText()))
                    .findFirst().orElseThrow();
            register.performClick();
            assertEquals(2, content.getTouchables().stream().filter(EditText.class::isInstance).count());
        }
    }
}
