/*jshint undef: false, unused: false, indent: 2*/
/*global angular: false */

'use strict';

angular.module('hopsWorksApp')
        .controller('MainCtrl', ['$interval', '$cookies', '$location', '$scope', '$rootScope',
          'AuthService', 'UtilsService', 'ElasticService', 'DelaProjectService',
          'DelaService', 'md5', 'ModalService', 'ProjectService', 'growl',
          'MessageService', '$routeParams', '$window', 'HopssiteService',
          function ($interval, $cookies, $location, $scope, $rootScope, AuthService, UtilsService,
                  ElasticService, DelaProjectService, DelaService, md5, ModalService,
                  ProjectService, growl,
                  MessageService, $routeParams, $window, HopssiteService) {

            var self = this;
            self.email = $cookies.get('email');
            self.emailHash = md5.createHash(self.email || '');
            var elasticService = ElasticService();

            if (!angular.isUndefined($routeParams.datasetName)) {
              self.searchType = "datasetCentric";
            } else if (!angular.isUndefined($routeParams.projectID)) {
              self.searchType = "projectCentric";
            } else {
              self.searchType = "global";
            }

            self.isAdmin = function () {
              return $cookies.get('isAdmin');
            };

            self.goToAdminPage = function () {
              $window.location.href = '/hopsworks-admin/security/protected/admin/adminIndex.xhtml';
            };

            self.getEmailHash = function (email) {
              return md5.createHash(email || '');
            };

            self.logout = function () {
              AuthService.logout(self.user).then(
                      function (success) {
                        $location.url('/login');
                        $cookies.remove("email");
                        $cookies.remove("isAdmin");
                        localStorage.removeItem("SESSIONID");
                        sessionStorage.removeItem("SESSIONID");
                      }, function (error) {
                self.errorMessage = error.data.msg;
              });
            };
            
            var checkDelaEnabled = function () {
              HopssiteService.getServiceInfo("dela").then(function (success) {
                console.log("isDelaEnabled", success);
                self.delaServiceInfo = success.data;
                if (self.delaServiceInfo.status === 1 ) {
                  $rootScope['isDelaEnabled'] = true;
                } else {
                  $rootScope['isDelaEnabled'] = false;
                }
              }, function (error) {
                $rootScope['isDelaEnabled'] = false;
                console.log("isDelaEnabled", error);
              });
            };
            checkDelaEnabled(); // check 

            self.profileModal = function () {
              ModalService.profile('md');
            };

            self.sshKeysModal = function () {
              ModalService.sshKeys('lg');
            };

            self.getHostname = function () {
              return $location.host();
            };

            self.getUser = function () {
              return self.email.substring(0, self.email.indexOf("@"));
            };

            var getUnreadCount = function () {
              MessageService.getUnreadCount().then(
                      function (success) {
                        self.unreadMessages = success.data.data.value;
                      }, function (error) {
              });
            };

            var getMessages = function () {
              MessageService.getMessages().then(
                      function (success) {
                        self.messages = success.data;
                      }, function (error) {
              });
            };

            getUnreadCount();
            getMessages();

            var getUnreadCountInterval = $interval(function () {
              getUnreadCount();
            }, 10000);

            self.getMessages = function () {
              getMessages();
            };

            self.openMessageModal = function (selected) {
              if (selected !== undefined) {
                MessageService.markAsRead(selected.id);
              }
              ;
              ModalService.messages('lg', selected)
                      .then(function (success) {
                        growl.success(success.data.successMessage,
                                {title: 'Success', ttl: 1000});
                      }, function (error) {
                      });
            };

            self.searchTerm = "";
            self.searching = false;
            self.globalClusterBoundary = false;
            self.searchReturned = "";
            self.searchReturnedPublicSearch = "";
            self.searchResult = [];
            self.searchResultPublicSearch = [];
            self.resultPages = 0;
            self.resultPagesPublicSearch = 0;
            self.resultItems = 0;
            self.resultItemsPublicSearch = 0;
            self.currentPage = 1;
            self.pageSize = 16;

            self.hitEnter = function (evt) {
              if (angular.equals(evt.keyCode, 13) && !self.searching) {
                self.searchResult = [];
                self.search();
              } else if (angular.equals(evt.keyCode, 27)) {
                self.clearSearch();
              }
            };

            self.keyTyped = function (evt) {
              if (self.searchTerm.length > 3
                      || (self.searchResult.length > 0 && self.searchTerm.length > 0)) {
                self.searchReturned = "Searching for <b>" + self.searchTerm + "<b> ...";
                self.searchResult = [];
                self.search();
              } else {
                self.searchResult = [];
                self.searchReturned = "";
                self.searchResultPublicSearch = [];
                self.searchReturnedPublicSearch = "";
              }
            };

            self.clearSearch = function () {
              self.searchResult = [];
              self.searchReturned = "";
              self.searchTerm = "";
            };

            self.search = function () {
              //ask for the project name when it is time to search
              self.projectName = UtilsService.getProjectName();
              self.currentPage = 1;
              self.pageSize = 16;
              self.searchResult = [];

              if (self.searchTerm === undefined || self.searchTerm === "" || self.searchTerm === null) {
                return;
              }
              self.searching = true;
              if (self.searchType === "global" && $rootScope.isDelaEnabled) {
                var global_data;
                var searchHits;
                //triggering a global search
                self.searchResult = [];
                elasticService.globalSearch(self.searchTerm)
                        .then(function (response) {
                          searchHits = response.data;
                          if (searchHits.length > 0) {
                            self.searchResult = searchHits;
                          } else {
                            self.searchResult = [];
                          }
                          self.resultPages = Math.ceil(self.searchResult.length / self.pageSize);
                          self.resultItems = self.searchResult.length;
                          DelaService.search(self.searchTerm).then(function (response2) {
                            global_data = response2.data;
                            if (global_data.length > 0) {
                              self.searchResult = concatUnique(searchHits, global_data);
                              self.searching = false;
                            } else {
                              self.searching = false;
                            }
                            self.resultPages = Math.ceil(self.searchResult.length / self.pageSize);
                            self.resultItems = self.searchResult.length;
                          });
                        }, function (error) {
                          self.searching = false;
                          growl.error(error.data.errorMsg, {title: 'Error', ttl: 5000});
                        });
              } else if (self.searchType === "global" && !$rootScope.isDelaEnabled) {
                var searchHits;
                //triggering a global search
                self.searchResult = [];
                elasticService.globalSearch(self.searchTerm)
                        .then(function (response) {
                          searchHits = response.data;
                          if (searchHits.length > 0) {
                            self.searchResult = searchHits;
                          } else {
                            self.searchResult = [];
                          }
                          self.searching = false;
                          self.resultPages = Math.ceil(self.searchResult.length / self.pageSize);
                          self.resultItems = self.searchResult.length;                          
                        }, function (error) {
                          self.searching = false;
                          growl.error(error.data.errorMsg, {title: 'Error', ttl: 5000});
                        });
              } else if (self.searchType === "projectCentric") {
                elasticService.projectSearch($routeParams.projectID, self.searchTerm)
                        .then(function (response) {
                          self.searching = false;
                          var searchHits = response.data;
                          if (searchHits.length > 0) {
                            self.searchResult = searchHits;
                          } else {
                            self.searchResult = [];
                          }
                          self.resultPages = Math.ceil(self.searchResult.length / self.pageSize);
                          self.resultItems = self.searchResult.length;
                        }, function (error) {
                          self.searching = false;
                          growl.error(error.data.errorMsg, {title: 'Error', ttl: 5000});
                        });
              } else if (self.searchType === "datasetCentric") {
                elasticService.datasetSearch($routeParams.projectID, $routeParams.datasetName, self.searchTerm)
                        .then(function (response) {
                          self.searching = false;
                          var searchHits = response.data;
                          if (searchHits.length > 0) {
                            self.searchResult = searchHits;
                          } else {
                            self.searchResult = [];
                          }
                          self.resultPages = Math.ceil(self.searchResult.length / self.pageSize);
                          self.resultItems = self.searchResult.length;
                        }, function (error) {
                          self.searching = false;
                          growl.error(error.data.errorMsg, {title: 'Error', ttl: 5000});
                        });
              }
              datePicker();// this will load the function so that the date picker can call it.
            };

            var concatUnique = function (a, array2) {
              a = a.concat(array2);
              for (var i = 0; i < a.length; ++i) {
                for (var j = i + 1; j < a.length; ++j) {
                  if (!(a[i].publicId === undefined || a[i].publicId === null) &&
                          a[i].publicId === a[j].publicId)
                    a.splice(j--, 1);
                }
              }
              return a;
            };

            var datePicker = function () {
              $(function () {
                $('[type="datepicker"]').datetimepicker({format: 'DD/MM/YYYY'});
                $("#datepicker1").on("dp.change", function (e) {
                  $('#datepicker2').data("DateTimePicker").minDate(e.date);
                });
                $("#datepicker2").on("dp.change", function (e) {
                  $('#datepicker1').data("DateTimePicker").maxDate(e.date);
                });
                $("#datepicker3").on("dp.change", function (e) {
                  $('#datepicker4').data("DateTimePicker").minDate(e.date);
                });
                $("#datepicker4").on("dp.change", function (e) {
                  $('#datepicker3').data("DateTimePicker").maxDate(e.date);
                });
              });
            };

            self.viewType = function (listView) {
              if (listView) {
                self.pageSize = 4;
              } else {
                self.pageSize = 6;
              }
            };

            $scope.$on("$destroy", function () {
              $interval.cancel(getUnreadCountInterval);
              //$interval.cancel(getPopularPublicDatasetsInterval);
            });

            self.downloadPublicDataset = function (result) {
              ModalService.selectProject('md', true, "/[^]*/", "Select a Project as download destination.").then(function (success) {
                var destProj = success.projectId;
                ModalService.setupDownload('md', destProj, result).then(function (success) {
                }, function (error) {
                  if (error.data && error.data.details) {
                    growl.error(error.data.details, {title: 'Error', ttl: 1000});
                  }
                  self.delaHopsService = new DelaProjectService(destProj);
                  self.delaHopsService.unshareFromHops(result.publicId, true).then(function (success) {
                    growl.info("Download cancelled.", {title: 'Info', ttl: 1000});
                  }, function (error) {
                    growl.warning(error, {title: 'Warning', ttl: 1000});
                  });
                });
              }, function (error) {
              });
            };

            self.viewType = function (listView) {
              if (listView) {
                self.pageSize = 4;
              } else {
                self.pageSize = 16;
              }
            };

            self.incrementPage = function () {
              self.pageSize = self.pageSize + 1;
            };

            self.decrementPage = function () {
              if (self.pageSize < 2) {
                return;
              }
              self.pageSize = self.pageSize - 1;
            };

            self.viewDetail = function (result) {
              if (result.localDataset) {
                if (result.type === 'proj') {
                  ProjectService.getProjectInfo({projectName: result.name}).$promise.then(
                          function (response) {
                            ModalService.viewSearchResult('lg', response, result);
                          }, function (error) {
                    growl.error(error.data.errorMsg, {title: 'Error', ttl: 5000});
                  });
                } else if (result.type === 'ds') {
                  ProjectService.getDatasetInfo({inodeId: result.id}).$promise.then(
                          function (response) {
                            var projects;
                            ProjectService.query().$promise.then(
                                    function (success) {
                                      projects = success;
                                      ModalService.viewSearchResult('lg', response, result, projects);
                                    }, function (error) {
                              growl.error(error.data.errorMsg, {title: 'Error', ttl: 5000});
                            });
                          });
                } else if (result.type === 'inode') {
                  ProjectService.getInodeInfo({id: $routeParams.projectID, inodeId: result.id}).$promise.then(
                          function (response) {
                            var projects;
                            ProjectService.query().$promise.then(
                                    function (success) {
                                      projects = success;
                                      ModalService.viewSearchResult('lg', response, result, projects);
                                    }, function (error) {
                              growl.error(error.data.errorMsg, {title: 'Error', ttl: 5000});
                            });
                          });
                }
              } else {
                ModalService.viewSearchResult('lg', result, result, null);
              }
            };
          }]);
