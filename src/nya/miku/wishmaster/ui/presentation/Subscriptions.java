/*
 * Overchan Android (Meta Imageboard Client)
 * Copyright (C) 2014-2016  miku-nyan <https://github.com/miku-nyan>
 *     
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package nya.miku.wishmaster.ui.presentation;

import java.util.Arrays;
import java.util.regex.Matcher;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;
import nya.miku.wishmaster.api.models.UrlPageModel;
import nya.miku.wishmaster.cache.SerializablePage;
import nya.miku.wishmaster.common.Logger;
import nya.miku.wishmaster.common.MainApplication;

public class Subscriptions {
    private static final String TAG = "Subscriptions";
    private SubscriptionsDB database;
    private Object[] cached;
    
    public Subscriptions(Context context) {
        database = new SubscriptionsDB(context);
    }
    
    /**
     * Получить текущее количество подписок (отслеживаемых постов)
     */
    public long getCurrentCount() {
        return database.getNumEntries();
    }
    
    /**
     * Проверить, есть ли на данной странице ответы на отслеживаемые посты
     * @param page страница
     * @param startPostIndex номер поста (по порядку) на странице, начиная с которого требуется проверять
     */
    public boolean checkSubscriptions(SerializablePage page, int startPostIndex) {
        if (!MainApplication.getInstance().settings.isSubscriptionsEnabled()) return false;
        if (page.pageModel == null || page.pageModel.type != UrlPageModel.TYPE_THREADPAGE || page.posts == null)
            return false;
        String[] subscriptions = getSubscriptions(page.pageModel.chanName, page.pageModel.boardName, page.pageModel.threadNumber);
        if (subscriptions == null) return false;
        if (startPostIndex < page.posts.length &&
                MainApplication.getInstance().settings.subscribeThreads() &&
                Arrays.binarySearch(subscriptions, page.pageModel.threadNumber) >= 0)
            return true;
        for (int i=startPostIndex; i<page.posts.length; ++i) {
            String comment = page.posts[i].comment;
            if (comment == null) continue;
            Matcher m = PresentationItemModel.REPLY_LINK_FULL_PATTERN.matcher(comment);
            while (m.find()) if (Arrays.binarySearch(subscriptions, m.group(1)) >= 0) return true;
        }
        return false;
    }
    
    /**
     * Добавить отслеживаемый пост
     */
    public void addSubscription(String chan, String board, String thread, String post) {
        database.put(chan, board, thread, post);
        Object[] tuple = cached;
        if (tuple != null && tuple[0].equals(chan) && tuple[1].equals(board) && tuple[2].equals(thread))
            cached = null;
    }
    
    /**
     * Проверить, является ли пост отслеживаемым
     */
    public boolean hasSubscription(String chan, String board, String thread, String post) {
        return database.hasSubscription(chan, board, thread, post);
    }
    
    /**
     * Удалить отслеживаемый пост
     */
    public void removeSubscription(String chan, String board, String thread, String post) {
        database.remove(chan, board, thread, post);
        Object[] tuple = cached;
        if (tuple != null && tuple[0].equals(chan) && tuple[1].equals(board) && tuple[2].equals(thread))
            cached = null;
    }
    
    /**
     * Получить отсортированный список отслеживаемых постов в данном треде
     * @return массив, отсортированный как массив java.lang.String
     */
    public String[] getSubscriptions(String chan, String board, String thread) {
        Object[] tuple = cached;
        if (tuple != null && tuple[0].equals(chan) && tuple[1].equals(board) && tuple[2].equals(thread))
            return (String[]) tuple[3];
        String[] result = database.getSubscriptions(chan, board, thread);
        if (result == null || result.length == 0) result = null; else Arrays.sort(result);
        cached = new Object[] { chan, board, thread, result };
        return result;
    }
    
    /**
     * Очистить все подписки (отслеживаемые посты)
     */
    public void reset() {
        database.resetDB();
        cached = null;
    }
    
    private static class SubscriptionsDB {
        private static final int DB_VERSION = 1000;
        private static final String DB_NAME = "subscriptions.db";
        
        private static final String TABLE_NAME = "subscriptions";
        private static final String COL_CHAN = "chan";
        private static final String COL_BOARD = "board";
        private static final String COL_THREAD = "thread";
        private static final String COL_POST = "post";
        
        private final DBHelper dbHelper; 
        public SubscriptionsDB(Context context) {
            dbHelper = new DBHelper(context);
        }
        
        public boolean hasSubscription(String chan, String board, String thread, String post) {
            Cursor c = dbHelper.getReadableDatabase().query(TABLE_NAME, null,
                    COL_CHAN + " = ? AND " + COL_BOARD + " = ? AND " + COL_THREAD + " = ? AND " + COL_POST + " = ?",
                    new String[] { chan, board, thread, post }, null, null, null);
            boolean result = false;
            if (c != null && c.moveToFirst()) result = true;
            if (c != null) c.close();
            return result;
        }
        
        public void put(String chan, String board, String thread, String post) {
            if (hasSubscription(chan, board, thread, post)) {
                Logger.d(TAG, "entry is already exists");
                return;
            }
            ContentValues value = new ContentValues(4);
            value.put(COL_CHAN, chan);
            value.put(COL_BOARD, board);
            value.put(COL_THREAD, thread);
            value.put(COL_POST, post);
            dbHelper.getWritableDatabase().insert(TABLE_NAME, null, value);
        }
        
        public void remove(String chan, String board, String thread, String post) {
            dbHelper.getWritableDatabase().delete(TABLE_NAME,
                    COL_CHAN + " = ? AND " + COL_BOARD + " = ? AND " + COL_THREAD + " = ? AND " + COL_POST + " = ?",
                    new String[] { chan, board, thread, post });
        }
        
        public String[] getSubscriptions(String chan, String board, String thread) {
            Cursor c = dbHelper.getReadableDatabase().query(TABLE_NAME, null,
                    COL_CHAN + " = ? AND " + COL_BOARD + " = ? AND " + COL_THREAD + " = ?",
                    new String[] { chan, board, thread }, null, null, null);
            String[] result = null;
            if (c != null && c.moveToFirst()) {
                int postIndex = c.getColumnIndex(COL_POST);
                int count = c.getCount();
                result = new String[count];
                int i = 0;
                do result[i++] = c.getString(postIndex); while (i < count && c.moveToNext());
                if (i < count) {
                    Logger.e(TAG, "result size < cursor getCount()");
                    String[] tmp = new String[i];
                    System.arraycopy(result, 0, tmp, 0, i);
                    result = tmp;
                }
            }
            if (c != null) c.close();
            return result;
        }
        
        public void resetDB() {
            dbHelper.resetDB();
        }
        
        public long getNumEntries() {
            return DatabaseUtils.queryNumEntries(dbHelper.getReadableDatabase(), TABLE_NAME);
        }
        
        private static class DBHelper extends SQLiteOpenHelper implements BaseColumns {
            public DBHelper(Context context) {
                super(context, DB_NAME, null, DB_VERSION);
            }
            
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL(createTable(TABLE_NAME, new String[] { COL_CHAN, COL_BOARD, COL_THREAD, COL_POST }, null));
            }
            
            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                if (oldVersion < newVersion) {
                    db.execSQL(dropTable(TABLE_NAME));
                    onCreate(db);
                }
            }
            
            @Override
            public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                onUpgrade(db, oldVersion, newVersion);
            }
            
            private static String createTable(String tableName, String[] columns, String[] types) {
                StringBuilder sql = new StringBuilder(110).append("create table ").append(tableName).append(" (").
                        append(_ID).append(" integer primary key autoincrement,");
                for (int i=0; i<columns.length; ++i) {
                    sql.append(columns[i]).append(' ').append(types == null ? "text" : types[i]).append(',');
                }
                sql.setCharAt(sql.length()-1, ')');
                return sql.append(';').toString();
            }
            
            private static String dropTable(String tableName) {
                return "DROP TABLE IF EXISTS " + tableName;
            }
            
            private void resetDB() {
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL(dropTable(TABLE_NAME));
                onCreate(db);
            }
        }
    }
    
}
