package in.anoopbhatia.mail;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

final class AppBroadcastManager {
    private final Context context;

    private AppBroadcastManager(Context context) {
        this.context = context.getApplicationContext();
    }

    static AppBroadcastManager getInstance(Context context) {
        return new AppBroadcastManager(context);
    }

    void sendBroadcast(Intent intent) {
        Intent scoped = new Intent(intent).setPackage(context.getPackageName());
        context.sendBroadcast(scoped);
    }

    void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    void unregisterReceiver(BroadcastReceiver receiver) {
        context.unregisterReceiver(receiver);
    }
}
