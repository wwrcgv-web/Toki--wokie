package ml.toki.walkie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(i.getAction())) return;
        SmsMessage[] ms = Telephony.Sms.Intents.getMessagesFromIntent(i);
        if (ms == null || ms.length == 0) return;
        String from = ms[0].getOriginatingAddress();
        StringBuilder sb = new StringBuilder();
        for (SmsMessage m : ms) sb.append(m.getMessageBody());
        String body = sb.toString();
        if (!body.startsWith("TW:")) return;
        MainActivity.saveMsg(c, "SMS de " + from + " : " + body.substring(3));
    }
}
