/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __LUNEXSU_APP_SESSION_H
#define __LUNEXSU_APP_SESSION_H

#include <linux/types.h>

void ksu_app_session_init(void);
void ksu_app_session_exit(void);
void ksu_app_session_update_current(void);
void ksu_app_session_leave_current(void);
int ksu_app_session_bind(uid_t uid, u64 *epoch);
bool ksu_app_session_available(uid_t uid, u64 epoch, bool admission);

#endif
